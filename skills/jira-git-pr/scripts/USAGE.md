# Using the Jira + Git + PR Skill

## Quick Start

Simply tell Claude what you want to create:

```
Create a Jira task in LMMS for "Add two-factor authentication"
Description: "Implement TOTP-based 2FA with backup codes"
Branch it in kit-web and open a PR
```

Claude will:
1. Create the Jira issue (via Atlassian Rovo)
2. Extract the issue key (e.g., LMMS-124)
3. Create a branch named `feat/LMMS-124-add-two-factor-authentication`
4. Push the branch
5. Open a GitHub PR with the description from Jira

## What Happens Behind the Scenes

### Step 1: Jira Issue Creation
Claude uses Atlassian Rovo's `createJiraIssue` tool to create the task:
- **Project**: You specify (e.g., LMMS)
- **Issue Type**: Bug, Task, or Story (defaults to Task)
- **Summary**: Your title
- **Description**: Your full description

Response includes the issue key (e.g., `LMMS-124`).

### Step 2: Branch Creation
Claude runs the workflow script locally:
```bash
git fetch origin
git checkout -b feat/LMMS-124-add-two-factor-authentication origin/main
git push -u origin feat/LMMS-124-add-two-factor-authentication
```

### Step 3: PR Creation
Claude calls GitHub's API with:
- **Title**: `LMMS-124: Add two-factor authentication`
- **Body**: The Jira description
- **Head**: Your feature branch
- **Base**: Usually `main` (you can change it)

## Requirements

### Atlassian Connection
- ✅ Already connected (Atlassian Rovo in your integrations)
- Your Jira project key (e.g., LMMS)

### GitHub Access
One of:
1. **GitHub CLI installed** (`gh auth status` shows you're logged in)
2. **`GITHUB_TOKEN` env var set** with `repo` scope
3. **`~/.ssh/id_rsa`** for git SSH (needs passphrase or ssh-agent)

If GitHub access fails, the branch is still created — you just need to create the PR manually.

### Git Repo Access
- The repo must be cloned locally
- You must have write access to the remote

## Examples

### Example 1: Simple feature task
```
Jira project: LMMS
Repo: ~/jchiuindustries/kit-web
Task: "Improve home page loading speed"
Description: "Optimize image lazy loading and reduce bundle size"
```

Claude creates:
- Jira issue: LMMS-125
- Branch: `feat/LMMS-125-improve-home-page-loading-speed`
- PR with the description linked to that branch

### Example 2: Bug fix with custom base branch
```
I need a branch for kit-mp-firmware off the "release/v2.1" branch
Jira task: "Fix calibration drift in sensor module"
Description: "Address reported drift in Z-axis after 8 hours of operation"
```

Claude creates:
- Jira issue: LMMS-126 (or whatever project key you use)
- Branch: `feat/LMMS-126-fix-calibration-drift-in-sensor-module` (off `release/v2.1`)
- PR targeting `release/v2.1`

## Customization

### Change branch naming
If you prefer `bugfix/KEY-title` instead of `feat/KEY-title`, tell Claude:
```
Use branch prefix "bugfix/" instead of "feat/" for this one
```

### Change issue type
By default, new tasks are created as type "Task". You can request:
```
Make this a Story instead of a Task
```

### Skip PR creation
If you just want the Jira issue and branch, no PR:
```
Just create the Jira issue and branch, don't open a PR yet
```

## Troubleshooting

### "Branch already exists"
Delete it first:
```bash
git branch -D feat/LMMS-124-...
git push origin --delete feat/LMMS-124-...
```

Then re-run.

### "GitHub API error: 401 Unauthorized"
Your token is invalid or expired. Refresh it:
```bash
# If using GitHub CLI
gh auth refresh
gh auth status

# Or set a new token
export GITHUB_TOKEN=ghp_...
```

### "Could not parse GitHub owner/repo from remote URL"
The script uses your git remote URL. Check:
```bash
git remote -v
```

If it's SSH (`git@github.com:...`) and you don't have SSH set up, add HTTPS:
```bash
git remote set-url origin https://github.com/owner/repo.git
```

### Jira issue created but branch/PR failed
The Jira issue exists; you can create the branch manually:
```bash
git checkout -b feat/LMMS-124-title origin/main
git push -u origin feat/LMMS-124-title
```

Then open the PR in the GitHub web UI.

## Command-Line Invocation (If Needed)

If you want to run the script directly without Claude:

```bash
node scripts/jira-git-pr.js \
  --project LMMS \
  --repo ~/jchiuindustries/kit-web \
  --title "Your task title" \
  --description "Full description here" \
  --base-branch main \
  --github-token $GITHUB_TOKEN
```

But typically, just describe what you want to Claude and it handles it.
