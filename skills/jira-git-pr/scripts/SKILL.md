---
name: jira-git-pr-workflow
description: Create a Jira task, then automatically create a matching git branch (feat/ISSUE-KEY-title) and a GitHub PR with the same description. Use this whenever you need to set up a new feature/task that requires tracking in both Jira and GitHub simultaneously. Handles the full workflow from task creation through PR opening.
---

# Jira + Git Branch + PR Workflow

This skill automates the common pattern of creating a Jira task and immediately opening a matching branch and pull request in GitHub.

## What This Does

1. **Creates a Jira issue** with your provided title and description
2. **Generates a git branch** named `feat/{ISSUE_KEY}-{title-slug}` (e.g., `feat/LMMS-123-add-feature`)
3. **Creates a GitHub PR** with the Jira description auto-filled
4. Returns all the URLs and keys for your reference

## Prerequisites

- **Jira project key** (e.g., LMMS, PROJ) — you specify this when running
- **GitHub token** with `repo` scope (must be set in your environment or provided)
- **Git repo** cloned locally and ready to work in (you specify the path)
- **Atlassian Rovo** connected to Claude

## How to Use

When you want to start a new task:

1. **Call this skill** with:
   - `project_key`: Your Jira project (e.g., "LMMS")
   - `repo_path`: Local path to the repo (e.g., "./kit-web")
   - `issue_title`: Task title (e.g., "Add user authentication")
   - `issue_description`: Full description
   - `base_branch`: Default branch to branch from (default: "main")

2. **Example:**
   ```
   Create a Jira + branch + PR for the "LMMS" project.
   Repo: ~/jchiuindustries/kit-web
   Title: "Implement OAuth2 flow"
   Description: "Add OAuth2 support with Google and GitHub providers"
   ```

3. The skill will:
   - Create the Jira issue
   - Extract the issue key (e.g., LMMS-123)
   - Create and push the branch
   - Open the PR with matching description

## Execution Script

The actual work happens in a bash + API script. Here's the flow:

```bash
# 1. Create Jira issue (via Atlassian Rovo MCP or API)
# 2. Extract issue key from response
# 3. Slug the title for branch naming
# 4. git fetch origin / git checkout -b feat/KEY-slug
# 5. git push -u origin feat/KEY-slug
# 6. Create GitHub PR via API (title + description from Jira)
```

## GitHub Token Setup

If GitHub token not in environment:
- Check `gh auth status` (GitHub CLI)
- Or set `GITHUB_TOKEN` env var
- Minimal required scopes: `repo` (full control)

If you hit a "401 Unauthorized" during PR creation, verify your token and scopes.

## Edge Cases

- **Branch already exists**: Script will warn and suggest deleting the old branch
- **Repo path invalid**: Script checks before proceeding
- **Jira issue creation fails**: Error details returned, no branch created
- **PR creation fails but branch exists**: You can retry PR creation separately

## Output

Returns:
- Jira issue URL
- Issue key (e.g., LMMS-123)
- Branch name
- Git remote URL
- GitHub PR URL (once created)

## Customization

To modify branch naming convention (currently `feat/KEY-slug`), edit the slug function in the script to use a different prefix (e.g., `task/`, `bugfix/`) or naming style.
