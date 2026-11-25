# PhotoHub Archiver — Design Document

## Implemented behaviour (supersedes the draft below)

The archiver now runs **inside PhotoHub itself** on Android and desktop, as an
occasional, user-triggered action in Sources → "Archive old photos to NAS". It
is not a separate bash/Python service and has no schedule.

For every linked Google Drive and MEGA instance, items older than the chosen
cutoff are downloaded, copied under
`<NAS remotePath>/PhotoHubArchive/<Cloud>/YYYY/MM/DD/`, and verified by reading
the upload back from the NAS and comparing SHA-256 hashes. Only after that is
the cloud original deleted (MEGA permanently, Drive via its delete API). A
failed copy or delete keeps the cloud original; the next run retries it. On
Android the run is refused unless the active connection is Wi-Fi/unmetered.
After a run, refresh the timeline so archived rows move from the cloud source
to the NAS source.

The draft below described an earlier standalone-service idea and is retained
for background only.

## Original draft (standalone service; not implemented)

Two deployment targets, same business logic:

| | NAS mode | Laptop mode |
|---|---|---|
| Target | QNAP TS-220 (ARMv5, 512MB, QTS 4.3.3) | Lenovo or any Linux laptop/server |
| Runtime | bash + rclone (single static binary) | Python 3 |
| Scheduler | QTS cron (`/etc/config/crontab`) | systemd timer, or cron, or built-in scheduler |
| State | flat log file + rclone cache | SQLite DB |
| Web UI | optional CGI script (sh/awk) | lightweight Flask/FastAPI web UI |
| Sources now | Google Drive, MEGA | same |
| Sources later | Koofr, pCloud, OneDrive, Dropbox | same |

---

## 1. What the archiver does

On a **schedule** (default: nightly at 02:00), for each configured cloud source:

1. **List** all photos/videos in the source.
2. **Filter** to items whose `dateTaken` < configured cutoff (e.g., older than 90 days).
3. **Check** whether source is reachable. If unreachable → **skip** that source, log a warning, continue with others.
4. **Download** filtered items to the NAS archive folder, preserving folder structure `YYYY/MM/DD/<filename>`.
5. **Verify** the downloaded file (SHA-256 checksum or file size match).
6. If verification passes → **delete from cloud source**.
7. If verification fails → **keep cloud copy**, log the error, skip delete.
8. Record each moved item in the **archive log** (source, sourceKey, dateTaken, archivePath, sha256, movedAt).

Non-goals:
- Never delete a cloud file without first verifying the local copy.
- Never silently fail — every skip and error is logged.
- Never touch items newer than the cutoff.
- No upload; archiver only moves cloud → NAS.

---

## 2. Core business logic (shared between modes)

Both modes implement the same pipeline. The difference is only in how state is stored and how the schedule is triggered.

```
for each enabled source:
    if not reachable(source):
        log("SKIP source unreachable")
        continue

    page = list_since(source, cursor=last_cursor)
    for item in page.items:
        if item.dateTaken >= cutoff_date:
            continue  # too recent, skip

        dest = archive_path(item)   # e.g. /archive/2024/03/15/IMG_1234.jpg
        if dest already exists and sha256 matches:
            mark_archived(item)     # de-duplicate without re-download
            delete_from_source(source, item)
            continue

        ok = download(source, item, dest)
        if not ok:
            log("DOWNLOAD FAILED", item)
            continue

        if verify(dest, item.expected_size):
            log("ARCHIVED", item)
            delete_from_source(source, item)
        else:
            log("VERIFY FAILED — keeping cloud copy", item)
            os.remove(dest)
```

### Date precedence
1. EXIF `DateTimeOriginal`
2. Provider metadata (`takenDateTime` on Drive, `m_time` on MEGA)
3. File mtime (last resort, logged as "mtime-fallback")

### Archive path layout
```
/archive/
  google-drive/
    2024/03/15/IMG_1234.jpg
    2024/03/15/IMG_1235.MOV
  mega/
    2023/11/02/DSC_0001.JPG
```
Source prefix is kept so origin is always traceable. Items with unknown date go to `_unknown-date/`.

---

## 3. NAS mode — QNAP TS-220

### Constraints
- ARMv5 (Marvell 1.6GHz), 512MB RAM, QTS 4.3.3 (EOL).
- No Container Station, no Python 3, no HBS 3.
- **Entware** provides `opkg` package manager on QTS.

### Tech stack
- **rclone** ARMv5 Linux binary (official download, no compilation needed).
- **bash** scripts (POSIX shell).
- **QTS cron** for scheduling.
- Flat **log file** (`/share/archive/photohub/archive.log`) for state.
- Optional: tiny **CGI script** to show job status at `http://10.0.0.15:8080/cgi-bin/photohub-status.sh`.

### Setup steps (one-time)
```sh
# 1. Install Entware on the NAS (community QPKG installer)
#    See: https://github.com/Entware/Entware/wiki/Install-on-QNAP-NAS

# 2. Install rclone via Entware (or manually)
opkg update
opkg install rclone
# Or: download armv5 binary from https://rclone.org/downloads/

# 3. Configure remotes (run from laptop, copy config to NAS)
rclone config  # set up 'gdrive' and 'mega' remotes
scp ~/.config/rclone/rclone.conf admin@10.0.0.15:/share/homes/admin/.config/rclone/

# 4. Create archive folder
mkdir -p /share/archive/photohub/logs

# 5. Install the archiver script (see below)
# 6. Add to QTS cron
```

### Archiver script: `photohub-archive.sh`
```sh
#!/bin/sh
# PhotoHub Archiver — NAS mode
# Runs via QTS cron. Moves photos older than CUTOFF_DAYS from cloud to NAS.

ARCHIVE_DIR="/share/archive/photohub"
LOG_FILE="$ARCHIVE_DIR/logs/archive-$(date +%Y%m%d).log"
CUTOFF_DAYS=90           # archive items older than this many days
SOURCES="gdrive mega"    # rclone remote names

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$LOG_FILE"; }

for SRC in $SOURCES; do
    log "INFO  [$SRC] checking reachability..."
    if ! rclone lsd "$SRC:" --contimeout 10s --timeout 10s > /dev/null 2>&1; then
        log "SKIP  [$SRC] unreachable — skipping"
        continue
    fi

    log "INFO  [$SRC] listing items older than $CUTOFF_DAYS days..."
    rclone lsjson "$SRC:" \
        --recursive \
        --max-age "${CUTOFF_DAYS}d" \
        --include "*.{jpg,jpeg,png,heic,mp4,mov,avi,3gp,gif,webp}" \
    | awk -v src="$SRC" -v archive="$ARCHIVE_DIR" -v log_file="$LOG_FILE" '
        # parse lsjson output — each line is a JSON object
        # extract Name, ModTime, Path; compute dest; call rclone copy+delete
        # (awk drives a shell pipeline per item)
        # NOTE: replaced by a rclone+jq pipeline below if jq available
    '

    # Simpler version without jq: use rclone move with --max-age
    # rclone does NOT delete from source after copy; use sync or move carefully.
    # Strategy: rclone copy --max-age, then verify, then rclone delete.
    CUTOFF_DATE=$(date -d "$CUTOFF_DAYS days ago" +%Y-%m-%d 2>/dev/null \
        || date -v "-${CUTOFF_DAYS}d" +%Y-%m-%d)  # fallback for busybox date

    DEST="$ARCHIVE_DIR/$SRC"
    mkdir -p "$DEST"

    log "INFO  [$SRC] downloading items older than $CUTOFF_DATE to $DEST..."
    if rclone copy "$SRC:" "$DEST" \
        --max-age "${CUTOFF_DAYS}d" \
        --include "*.{jpg,jpeg,png,heic,mp4,mov,avi,3gp,gif,webp}" \
        --create-empty-src-dirs=false \
        --log-level INFO \
        --log-file "$LOG_FILE" \
        --transfers 2 \
        --checkers 4; then

        log "INFO  [$SRC] download complete, verifying with checksum..."
        VERIFY_LOG="$ARCHIVE_DIR/logs/verify-$(date +%Y%m%d).log"
        if rclone check "$SRC:" "$DEST" \
            --max-age "${CUTOFF_DAYS}d" \
            --include "*.{jpg,jpeg,png,heic,mp4,mov,avi,3gp,gif,webp}" \
            --log-file "$VERIFY_LOG" 2>&1; then

            log "INFO  [$SRC] verification passed, deleting from source..."
            rclone delete "$SRC:" \
                --max-age "${CUTOFF_DAYS}d" \
                --include "*.{jpg,jpeg,png,heic,mp4,mov,avi,3gp,gif,webp}" \
                --log-level INFO \
                --log-file "$LOG_FILE"
            log "OK    [$SRC] archive cycle complete"
        else
            log "ERROR [$SRC] verification FAILED — cloud copies NOT deleted"
            log "ERROR [$SRC] see $VERIFY_LOG for mismatches"
        fi
    else
        log "ERROR [$SRC] download failed — cloud copies NOT deleted"
    fi
done

log "INFO  all sources processed"
```

> **Limitation:** `rclone max-age` filters by **mtime on the remote**, not EXIF `dateTaken`.
> EXIF date parsing requires a separate step (see Laptop mode below).
> On the NAS, mtime is used as a pragmatic proxy. Items with wrong mtime go to `_unknown-date/` manually.

### QTS cron entry
Add via QTS Control Panel → Task Scheduler, or directly:
```
0 2 * * * admin /share/homes/admin/photohub/photohub-archive.sh >> /share/archive/photohub/logs/cron.log 2>&1
```

### Optional: CGI status endpoint
`/share/Web/cgi-bin/photohub-status.sh` → readable at `http://10.0.0.15:8080/cgi-bin/photohub-status.sh`

```sh
#!/bin/sh
echo "Content-Type: text/plain"
echo ""
echo "=== PhotoHub Archiver Status ==="
echo "Last run:"
tail -n 20 /share/archive/photohub/logs/archive-$(date +%Y%m%d).log 2>/dev/null \
    || echo "(no log today)"
echo ""
echo "Archive size:"
du -sh /share/archive/photohub/ 2>/dev/null
```
The phone app can poll this URL to show a "NAS archive last synced" status.

### Key limits on TS-220
- **No EXIF date parsing** — mtime proxy only.
- **No persistent DB** — log files only; can't query what's archived.
- **No dry-run UI** — must comment out the `rclone delete` line to test.
- **512MB RAM** — `--transfers 2 --checkers 4` to avoid OOM; no parallel source processing.
- **rclone check is not SHA-256** — it uses rclone's server-side hash where available (Drive supports MD5/SHA1; MEGA uses its own CRC). Add `--checksum` flag for maximum rigour.

---

## 4. Laptop mode — Lenovo (or any Linux)

### Constraints
- No constraints. Full Python 3 environment, persistent process, 8+ GB RAM.
- Can run as a **systemd service** or be triggered manually.

### Tech stack
- **Python 3.11+** (standard library + small deps).
- **rclone** as the cloud transport layer (same as NAS mode — no need to reinvent API auth).
- **SQLite** via `sqlite3` (stdlib) for archive manifest and job state.
- **ExifRead** or `piexif` for EXIF `DateTimeOriginal`.
- **FastAPI + Jinja2** for lightweight web UI + status API (the phone app can hit this).
- **APScheduler** (or systemd timer) for scheduling.
- **Rich** for console output.
- Optional: `apprise` for push/email/Slack notifications on job completion.

### Modules
```
photohub-archiver/
  archiver/
    __init__.py
    config.py          # YAML/TOML config loader
    db.py              # SQLite schema + DAOs
    sources.py         # rclone wrapper: listjson, copy, delete, check
    pipeline.py        # core archive loop (shared logic from §2)
    exif.py            # EXIF dateTaken extraction
    verify.py          # SHA-256 or rclone checksum verification
    scheduler.py       # APScheduler setup
    web.py             # FastAPI routes + Jinja2 templates
  config.example.toml
  main.py              # entrypoint: web + scheduler
  photohub-archive     # CLI entrypoint: run-now, dry-run, status
```

### SQLite schema
```sql
CREATE TABLE archived_items (
    id          TEXT PRIMARY KEY,       -- sha256 of (source + sourceKey)
    source      TEXT NOT NULL,          -- 'gdrive', 'mega', etc.
    source_key  TEXT NOT NULL,          -- remote path
    display_name TEXT NOT NULL,
    mime_type   TEXT,
    date_taken  INTEGER,                -- epoch ms, from EXIF or mtime
    date_source TEXT,                   -- 'exif', 'provider', 'mtime'
    size_bytes  INTEGER,
    sha256      TEXT,
    archive_path TEXT NOT NULL,
    archived_at INTEGER NOT NULL,       -- epoch ms
    deleted_from_source INTEGER,        -- 1 if deleted, 0 if kept
    UNIQUE(source, source_key)
);

CREATE TABLE archive_jobs (
    id          TEXT PRIMARY KEY,       -- UUID
    started_at  INTEGER NOT NULL,
    finished_at INTEGER,
    status      TEXT,                   -- 'running', 'ok', 'error', 'skipped'
    sources     TEXT,                   -- JSON array of source names
    items_archived INTEGER DEFAULT 0,
    items_failed   INTEGER DEFAULT 0,
    items_skipped  INTEGER DEFAULT 0,
    error_message  TEXT
);
```

### Config file (`config.toml`)
```toml
[archiver]
archive_dir     = "/mnt/archive/photohub"   # destination on this machine
cutoff_days     = 90                         # archive items older than N days
schedule_cron   = "0 2 * * *"               # nightly at 02:00
dry_run         = false                      # set true to preview without deleting
verify_checksum = true                       # SHA-256 verify before delete

[sources.gdrive]
enabled  = true
rclone_remote = "gdrive"               # name in rclone config
folder   = "/"                         # root of Drive to scan

[sources.mega]
enabled  = true
rclone_remote = "mega"
folder   = "/"

[web]
host = "0.0.0.0"
port = 8090
```

### Pipeline (Python, full EXIF support)
```python
async def run_archive_job(config: Config, dry_run: bool = False):
    job = db.start_job(sources=config.enabled_sources)
    for source_cfg in config.enabled_sources:
        try:
            if not await sources.is_reachable(source_cfg):
                log.warning(f"[{source_cfg.name}] unreachable — skipping")
                db.log_skip(job, source_cfg.name)
                continue

            async for item in sources.list_all(source_cfg):
                date_taken = exif.read(item.local_tmp) or item.mtime
                if date_taken >= config.cutoff_date:
                    continue  # too recent

                dest = archive_path(config.archive_dir, source_cfg.name, date_taken, item.name)
                if db.already_archived(source_cfg.name, item.source_key):
                    continue  # already done

                if not dry_run:
                    await sources.download(source_cfg, item, dest)
                    ok = verify.sha256_match(dest, item)
                    if ok:
                        db.record_archived(item, dest)
                        await sources.delete(source_cfg, item)
                        job.items_archived += 1
                    else:
                        log.error(f"[{source_cfg.name}] VERIFY FAIL {item.name} — cloud copy kept")
                        job.items_failed += 1
                else:
                    log.info(f"[DRY RUN] would archive {item.name} ({date_taken})")
        except Exception as e:
            log.error(f"[{source_cfg.name}] error: {e}")
            db.log_error(job, source_cfg.name, str(e))

    db.finish_job(job)
```

### Web UI routes
```
GET  /               → dashboard: last job status, next run time, source health
GET  /jobs           → job history list
GET  /jobs/<id>      → job detail with per-source item counts
GET  /archive        → paginated list of archived items (filterable by date, source)
POST /run            → trigger a job immediately (dry_run=true or false)
GET  /status.json    → machine-readable status (phone app polls this)
GET  /health         → 200 OK if running
```

`/status.json` example (phone app polls this as a "NAS source"):
```json
{
  "last_job": "2026-11-22T02:00:00Z",
  "status": "ok",
  "total_archived": 4821,
  "sources": {
    "gdrive": {"status": "ok", "last_seen": "2026-11-22T02:00:00Z"},
    "mega":   {"status": "ok", "last_seen": "2026-11-22T02:00:00Z"}
  }
}
```

### CLI
```sh
photohub-archive run              # run now (respects dry_run from config)
photohub-archive run --dry-run    # preview only, no downloads, no deletes
photohub-archive status           # print last job summary
photohub-archive list-archived    # dump archive manifest to stdout
photohub-archive doctor           # check rclone remotes, disk space, DB health
```

### Systemd service (laptop always-on)
```ini
[Unit]
Description=PhotoHub Archiver
After=network-online.target

[Service]
User=judchiu
WorkingDirectory=/home/judchiu/photohub-archiver
ExecStart=/home/judchiu/photohub-archiver/.venv/bin/python main.py
Restart=on-failure
RestartSec=60

[Install]
WantedBy=multi-user.target
```

---

## 5. NAS mode vs Laptop mode comparison

| Feature | NAS (TS-220) | Laptop |
|---|---|---|
| Runtime | bash + rclone | Python 3 + rclone |
| EXIF date parsing | ❌ mtime only | ✅ ExifRead |
| Delete verification | rclone `--checksum` (MD5/CRC) | ✅ SHA-256 |
| Duplicate detection | ❌ none | ✅ via SQLite |
| Dry-run mode | Manual (comment out delete) | ✅ `--dry-run` flag |
| Job history | Log files only | ✅ SQLite + web UI |
| Web status endpoint | Simple CGI text | ✅ JSON API |
| Cloud source down | ✅ skip + log | ✅ skip + DB record |
| New sources (later) | Update rclone config | Update config.toml |
| Resource use | ~20–40MB RAM | ~100MB RAM |
| Setup complexity | Medium (Entware + rclone) | Low (pip install) |

---

## 6. Milestones

| Milestone | NAS mode | Laptop mode |
|---|---|---|
| M1 | Install Entware + rclone on TS-220; configure `gdrive` and `mega` remotes; run script manually; verify log | Python scaffold: config, rclone wrapper, pipeline, SQLite; CLI `run --dry-run` works |
| M2 | Add QTS cron schedule; test download + verify + delete on a small folder; CGI status endpoint | EXIF date parsing; SHA-256 verify; full `run` command works; job history in DB |
| M3 | Add Koofr, pCloud sources; tune `--transfers` for 512MB ceiling | Web UI dashboard + `/status.json`; systemd service; notifications (apprise) |
| M4 | — | Phone app queries `/status.json`; NAS archive appears as 5th timeline source |

---

## 7. Risks and open decisions

- **rclone on TS-220**: Entware armv5 may have an older rclone build. Verify Drive/MEGA backend compatibility.
- **QTS date** builtin: `date -d` (GNU) may not work; `date -v` (BSD) is the busybox fallback. The script handles both.
- **MEGA rclone**: MEGA backend can be unstable with large libraries or rate-limited; build in retry logic (rclone has `--retries`).
- **Google Drive**: file `mtime` on Drive is "last modified in Drive", not `dateTaken`/EXIF. Without EXIF parsing (NAS mode), items edited in Drive after the cutoff won't be archived. Full EXIF parsing required for correctness — another reason to prefer laptop mode for the first real run.
- **Archive path collisions**: two files with same name, same date but different source. Handled by keeping source-prefixed folder.
- **Partial downloads**: if NAS crashes mid-download, incomplete files need cleanup. Add `.tmp` suffix during download, rename on complete.
- **Delete confirmation**: first run should always be `--dry-run` reviewed before enabling live delete.
- **Phone app integration**: phone app can read the NAS archive over SMB (already in design) + optionally poll `/status.json` from laptop mode. These are complementary.

---

## 8. Next step

1. Decide: run first on **NAS** or **Laptop**? Laptop mode is recommended for the first real run (EXIF support, dry-run, full visibility).
2. Configure `rclone` remotes for Google Drive and MEGA on whichever machine runs first.
3. Run `photohub-archive run --dry-run` and review what would be archived.
4. After dry-run review, enable live mode.
