---
name: memory-sync
description: Sync personal memories with a cloud-backed GitHub repository
license: MIT
compatibility: opencode
metadata:
  audience: personal
  workflow: knowledge-management
---

# memory-sync

Cloud-backed personal memory system synced to GitHub. Load, search, and save memories across Claude Code and opencode sessions.

## Setup

Configure your memory repo URL in `~/.claude/MEMORY.md` or environment:

```bash
export MEMORY_REPO_URL="https://github.com/username/memory-repo.git"
```

First time: `gh auth login` to enable GitHub access.

## Usage

**Load latest memories:**
```
/mem load [project]
```
Projects: `all` (default), or any subdirectory in `memories/`

**Search memories:**
```
/mem search "term"
```

**List available memories:**
```
/mem list [project]
```

**Save changes back to cloud:**
```
/mem push ["description"]
```

## How it works

1. Clones repo to `~/.cache/<tool>/memory-sync/repo`
2. Organizes memories by project in `memories/` subdirectories
3. `/mem search` finds relevant notes during work
4. `/mem push` commits and pushes new learnings back to GitHub

## File structure

Memories organized by project:
- `memories/main/` — Primary memories
- `memories/<project>/` — Project-specific notes
- Any `.md` files in these directories are automatically indexed

## Memory format

Optional YAML frontmatter (metadata only, not required):

```yaml
---
name: topic-name
description: Brief description
type: reference | decision | guide
---

# Content
...
```

Plain markdown works too.
