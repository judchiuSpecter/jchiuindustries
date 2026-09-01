#!/usr/bin/env bash
# Claude Code statusline: model | context | rate limits (with gauge) | enabled plugins (colored)
input=$(cat)

esc=$'\033'
c()  { printf '%s[38;5;%sm%s%s[0m' "$esc" "$1" "$2" "$esc"; }

# 0-100% -> colored block gauge: green < 50, yellow < 80, red above
gauge() {
  local pct=${1%.*} blocks='▁▂▃▄▅▆▇█' i color
  i=$(( pct / 13 )); (( i > 7 )) && i=7
  if   (( pct < 50 )); then color=71
  elif (( pct < 80 )); then color=179
  else color=167; fi
  c "$color" "${blocks:$i:1}"
}

# time left until a reset timestamp (unix epoch or ISO 8601), e.g. 2h13m / 43m
until_reset() {
  local end now diff
  if [[ $1 =~ ^[0-9]+$ ]]; then end=$1
  else end=$(date -d "$1" +%s 2>/dev/null) || return; fi
  now=$(date +%s); diff=$(( end - now ))
  (( diff <= 0 )) && return
  if (( diff >= 3600 )); then printf '↻%dh%02dm' $(( diff / 3600 )) $(( diff % 3600 / 60 ))
  else printf '↻%dm' $(( diff / 60 )); fi
}

IFS=$'\t' read -r dir model ctx h5 h5reset d7 < <(jq -r '[
  (.workspace.current_dir // .cwd // "-"),
  (.model.display_name // "-"),
  (.context_window.used_percentage // "-"),
  (.rate_limits.five_hour.used_percentage // "-"),
  (.rate_limits.five_hour.resets_at // "-"),
  (.rate_limits.seven_day.used_percentage // "-")
] | @tsv' <<<"$input")

files=(/home/judchiu/.claude/settings.json /home/judchiu/.claude/settings.local.json)
[ "$dir" != "-" ] && files+=("$dir/.claude/settings.json" "$dir/.claude/settings.local.json")
existing=()
for f in "${files[@]}"; do [ -f "$f" ] && existing+=("$f"); done

# one palette slot per plugin, by position in the sorted list -> no repeats until 10 plugins
palette=(110 150 180 175 145 216 108 141 209 116)
plugins=()
while read -r p; do
  [ -z "$p" ] && continue
  plugins+=("$(c "${palette[$(( ${#plugins[@]} % ${#palette[@]} ))]}" "$p")")
done < <(jq -rs '[.[] | (.enabledPlugins // {}) | to_entries[] | select(.value) | (.key | split("@")[0])] | unique | .[]' "${existing[@]}" 2>/dev/null)

parts=("$model")
[ "$ctx" != "-" ] && parts+=("Context: $(printf '%.0f' "$ctx")% $(gauge "$ctx")")
if [ "$h5" != "-" ]; then
  left=$(until_reset "$h5reset"); [ -n "$left" ] && left=" $(c 244 "$left")"
  parts+=("5h: $(printf '%.0f' "$h5")% $(gauge "$h5")$left")
fi
[ "$d7"  != "-" ] && parts+=("Week: $(printf '%.0f' "$d7")% $(gauge "$d7")")
[ ${#plugins[@]} -gt 0 ] && parts+=("$(IFS=,; echo "${plugins[*]}")")

(IFS='|'; echo "${parts[*]}" | sed 's/|/ | /g')
