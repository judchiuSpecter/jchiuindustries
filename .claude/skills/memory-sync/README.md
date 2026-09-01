# memory-sync skill

Portable memory sync for Claude Code and opencode.

## Installation

This is already in `.claude/skills/memory-sync/` for projects using jchiuindustries as a template.

## Quick start

Set your repo URL:
```bash
export MEMORY_REPO_URL="https://github.com/yourusername/your-memory-repo.git"
```

Then in Claude Code or opencode:
```
/mem load
/mem search "topic"
/mem push "notes about what I learned"
```

## For your own memory repo

1. Create a private GitHub repo (e.g., `your-username/your-memories`)
2. Clone it locally, add `memories/` directories and markdown files
3. Set `MEMORY_REPO_URL` in your shell or `.claude/CLAUDE.md`

## Script interface

```bash
memory-sync.sh load [subdir]      # Pull and report
memory-sync.sh list [subdir]      # List files
memory-sync.sh search <query>     # Grep across all
memory-sync.sh path               # Print repo path
memory-sync.sh push [message]     # Commit + push
```

The script auto-creates cache dirs and clones the repo on first run.

## How to wire commands

**In Claude Code** (`~/.claude/CLAUDE.md`):
```
@MEMORY_SYNC_CLAUDE.md
```

Then create `~/.claude/MEMORY_SYNC_CLAUDE.md`:
```yaml
@RTK.md

# Memory commands

- `/mem load` — pulls latest and shows location
- `/mem search <q>` — grep all memories
- `/mem list` — show all files
- `/mem push [msg]` — commit+push changes
```

**In opencode** (`~/.config/opencode/opencode.json`):
```json
{
  "command": {
    "mem": {
      "description": "Load, search, or push memories",
      "template": "The user wants to: $ARGUMENTS\n\nUse ~/.claude/skills/memory-sync/memory-sync.sh with MEMORY_REPO_URL set."
    }
  }
}
```

## Repo structure example

```
your-memory-repo/
  memories/
    Documents/
      *.md
    ProjectA/
      *.md
    ProjectB/
      *.md
    MEMORY.md       # index file
  README.md
```

Each project gets its own subdir. Run `/mem load [project]` to sync just that project.

## Caching

Memories are cloned to:
- Claude Code: `~/.cache/claude/memory-sync/repo/`
- opencode: `~/.cache/opencode/memory-sync/repo/`

Run `/mem load` to pull the latest from GitHub.
