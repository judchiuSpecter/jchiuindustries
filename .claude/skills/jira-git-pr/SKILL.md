---
name: jira-git-pr
description: Create a Jira bug/task issue and link it two-way with a GitHub PR (existing or new) — issue key referenced in the PR body, PR link posted as a Jira comment. Use whenever the user asks to "file a jira task for this", "make a jira bug and link the pr", or similar.
---

# Jira ↔ GitHub PR linking

Creates a Jira issue for the current change and cross-links it with a GitHub PR, using the Atlassian Rovo MCP tools and the `gh` CLI directly — no script, no separate GitHub token needed.

## Inputs to work out before acting

- **Jira project key** — ask the user if not obvious from context (check CLAUDE.md, memory, or prior issues referenced in this repo's commits/PRs).
- **Issue type** — `Bug` for a fix, `Task`/`Story` for new work. Infer from the change; ask if ambiguous.
- **Summary + description** — write these yourself from the diff/commits/conversation. Keep the description short: what broke or what's needed, root cause if known, and how it's fixed. Don't ask the user to dictate it unless they want to.
- **PR** — does one already exist for this branch, or does it need creating?

## Steps

1. **Get the Atlassian cloud ID** (once per session): `getAccessibleAtlassianResources`. Reuse it for both calls below.
2. **Find the project key** if not given: `getVisibleJiraProjects` with `searchString` narrowed to a likely name.
3. **Create the Jira issue**: `createJiraIssue` with `projectKey`, `issueTypeName`, `summary`, `description` (contentFormat: markdown). Note the returned issue key (e.g. `LMMS-1723`).
4. **Get or create the PR**:
   - If a PR already exists for the branch: `gh pr edit <number> --body "..."` — prepend/append a `Jira: <ISSUE-KEY>` line to the existing body (fetch current body with `gh pr view --json body` first, don't clobber it).
   - If no PR yet: commit + push the branch, then `gh pr create` with the Jira key referenced in the body (see kit-web's PR body conventions — Summary/Test plan sections).
5. **Link back from Jira to the PR**: `addCommentToJiraIssue` on the issue with the PR URL, e.g. "PR: https://github.com/OWNER/REPO/pull/N".
6. **Report** both URLs to the user (Jira issue link + PR link). Don't fabricate either — only report what the tool calls actually returned.

## Notes

- Never invent a Jira project key or cloud ID — look them up.
- Respect existing PR conventions in the target repo (draft vs ready, body sections, commit message style) — check `git log` and recent PRs before writing one from scratch.
- If GitHub push/PR creation would affect a shared branch (not a fresh feature branch), confirm with the user first — same caution as any other push.
