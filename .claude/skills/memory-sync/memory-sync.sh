#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${MEMORY_REPO_URL:-}"
[[ -z "$REPO_URL" ]] && { echo "Error: MEMORY_REPO_URL not set"; exit 1; }

TOOL="${MEMORY_TOOL:-opencode}"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/$TOOL/memory-sync"
REPO_DIR="$CACHE_DIR/repo"
MEM_DIR="$REPO_DIR/memories"

init_repo() {
  if [[ ! -d "$REPO_DIR/.git" ]]; then
    mkdir -p "$CACHE_DIR"
    git clone --quiet "$REPO_URL" "$REPO_DIR"
  fi
}

cmd_load() {
  init_repo
  git -C "$REPO_DIR" pull --quiet origin main
  local sub="${1:-.}"
  local count
  count=$(find "$MEM_DIR/$sub" -name '*.md' -type f 2>/dev/null | wc -l)
  echo "✓ $count memories available at $MEM_DIR/$sub"
  [[ -f "$MEM_DIR/$sub/MEMORY.md" ]] && echo "  Index: $MEM_DIR/$sub/MEMORY.md"
}

cmd_list() {
  init_repo
  local sub="${1:-.}"
  find "$MEM_DIR/$sub" -name '*.md' -type f -printf '%P\n' 2>/dev/null | sort
}

cmd_search() {
  init_repo
  local query="$1"
  grep -rn --include='*.md' --color=never "$query" "$MEM_DIR" 2>/dev/null | sed "s|$MEM_DIR/||" | head -20 || echo "No matches for: $query"
}

cmd_path() {
  echo "$MEM_DIR"
}

cmd_push() {
  init_repo
  local message="${1:-Update memories}"
  
  git -C "$REPO_DIR" add -A memories/
  if git -C "$REPO_DIR" diff --cached --quiet; then
    echo "No changes to push"
    return 0
  fi
  
  git -C "$REPO_DIR" commit --quiet -m "$message"
  git -C "$REPO_DIR" push --quiet origin main
  echo "✓ Pushed to $(basename "$REPO_URL")"
}

usage() {
  cat <<'EOF'
memory-sync — cloud-synced personal knowledge base

Commands:
  load [project]     Pull latest and report memory location
  list [project]     List memory files
  search <query>     Grep memories (returns file:line:match)
  path               Print the memories directory
  push [message]     Commit and push memory changes

Examples:
  memory-sync load
  memory-sync search "architecture"
  memory-sync push "Add new pattern"

Setup:
  export MEMORY_REPO_URL="https://github.com/user/memory-repo.git"
EOF
}

case "${1:-help}" in
  load) cmd_load "${2:-.}" ;;
  list) cmd_list "${2:-.}" ;;
  search) cmd_search "${2:?query required}" ;;
  path) cmd_path ;;
  push) cmd_push "${2:-Update memories}" ;;
  *) usage ;;
esac
