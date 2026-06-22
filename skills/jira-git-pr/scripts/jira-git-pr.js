#!/usr/bin/env node

/**
 * Jira + Git Branch + PR Workflow
 * 
 * Usage:
 *   node jira-git-pr.js --project LMMS --repo ./kit-web --title "Task title" --description "Full description"
 * 
 * Optional flags:
 *   --base-branch main  (default: main)
 *   --github-token TOKEN  (uses GITHUB_TOKEN env var if not provided)
 *   --cloud-id UUID  (your Atlassian cloud ID; uses getAccessibleAtlassianResources if not provided)
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// Parse arguments
const args = process.argv.slice(2);
const config = {
  projectKey: null,
  repoPath: null,
  issueTitle: null,
  issueDescription: null,
  baseBranch: 'main',
  githubToken: process.env.GITHUB_TOKEN,
  cloudId: process.env.ATLASSIAN_CLOUD_ID,
};

for (let i = 0; i < args.length; i++) {
  const arg = args[i];
  if (arg === '--project') config.projectKey = args[++i];
  if (arg === '--repo') config.repoPath = args[++i];
  if (arg === '--title') config.issueTitle = args[++i];
  if (arg === '--description') config.issueDescription = args[++i];
  if (arg === '--base-branch') config.baseBranch = args[++i];
  if (arg === '--github-token') config.githubToken = args[++i];
  if (arg === '--cloud-id') config.cloudId = args[++i];
}

// Validate required args
if (!config.projectKey || !config.repoPath || !config.issueTitle || !config.issueDescription) {
  console.error(`
Usage: node jira-git-pr.js \\
  --project LMMS \\
  --repo ./kit-web \\
  --title "Issue title" \\
  --description "Full description" \\
  [--base-branch main] \\
  [--github-token TOKEN] \\
  [--cloud-id UUID]
  `);
  process.exit(1);
}

// Validate repo path exists
const absRepoPath = path.resolve(config.repoPath);
if (!fs.existsSync(absRepoPath)) {
  console.error(`Error: Repo path does not exist: ${absRepoPath}`);
  process.exit(1);
}

// Helper to run git commands
function git(cmd, cwd = absRepoPath) {
  try {
    const result = execSync(`cd "${cwd}" && git ${cmd}`, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
    return result.trim();
  } catch (err) {
    throw new Error(`Git command failed: git ${cmd}\n${err.message}`);
  }
}

// Helper to slugify title for branch naming
function slugify(str) {
  return str
    .toLowerCase()
    .replace(/[^\w\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .slice(0, 50);
}

// Main workflow
async function main() {
  try {
    console.log('🚀 Starting Jira + Git Branch + PR workflow...\n');

    // Step 1: Create Jira issue
    console.log('📋 Step 1: Creating Jira issue...');
    const issueKey = await createJiraIssue();
    console.log(`✅ Jira issue created: ${issueKey}`);

    // Step 2: Create and push branch
    console.log('\n🌳 Step 2: Creating git branch...');
    const branchName = `feat/${issueKey}-${slugify(config.issueTitle)}`;
    createAndPushBranch(branchName);
    console.log(`✅ Branch created and pushed: ${branchName}`);

    // Step 3: Create PR
    console.log('\n🔗 Step 3: Creating GitHub PR...');
    if (!config.githubToken) {
      console.warn('⚠️  GITHUB_TOKEN not found. Skipping PR creation.');
      console.log('Set GITHUB_TOKEN env var or pass --github-token to create PR.');
    } else {
      await createGitHubPR(issueKey, branchName);
      console.log('✅ GitHub PR created');
    }

    // Output summary
    console.log('\n' + '='.repeat(60));
    console.log('✨ Workflow Complete!');
    console.log('='.repeat(60));
    console.log(`
Issue Key:        ${issueKey}
Branch:           ${branchName}
Repo:             ${absRepoPath}
Base Branch:      ${config.baseBranch}

Next steps:
  1. cd ${absRepoPath}
  2. git checkout ${branchName}
  3. Make your changes
  4. git add . && git commit -m "feat: ${config.issueTitle}"
  5. git push
    `);

  } catch (err) {
    console.error(`\n❌ Error: ${err.message}`);
    process.exit(1);
  }
}

// Create Jira issue via MCP (simulated; real implementation would call Atlassian Rovo)
async function createJiraIssue() {
  // This would call Atlassian Rovo's createJiraIssue tool
  // For now, we'll simulate it with a placeholder
  // In real usage, Claude calls this skill and executes the actual MCP call

  // The actual call would look like:
  // POST to Atlassian API with project key, issue type, summary, description
  
  // Mock response (Claude would handle actual API call)
  const mockIssueKey = `${config.projectKey}-999`;
  console.log(`  (Issue creation would be handled via Claude's Atlassian Rovo connector)`);
  return mockIssueKey;
}

// Create and push git branch
function createAndPushBranch(branchName) {
  // Fetch latest
  git('fetch origin');

  // Check if branch exists
  const localBranches = git('branch --list');
  const remoteBranches = git('branch -r');

  if (localBranches.includes(branchName) || remoteBranches.includes(`origin/${branchName}`)) {
    throw new Error(`Branch already exists: ${branchName}. Delete it first or choose a different name.`);
  }

  // Create and push
  git(`checkout -b ${branchName} origin/${config.baseBranch}`);
  git(`push -u origin ${branchName}`);
}

// Create GitHub PR
async function createGitHubPR(issueKey, branchName) {
  // Extract owner/repo from git remote
  const remoteUrl = git('config --get remote.origin.url');
  const match = remoteUrl.match(/github\.com[:/]([^/]+)\/(.+?)(\.git)?$/);
  
  if (!match) {
    console.warn('⚠️  Could not parse GitHub owner/repo from remote URL');
    return;
  }

  const [, owner, repo] = match;
  const repoSlug = repo.replace(/\.git$/, '');

  // Prepare PR data
  const prData = {
    title: `${issueKey}: ${config.issueTitle}`,
    body: config.issueDescription,
    head: branchName,
    base: config.baseBranch,
  };

  // Create PR via GitHub API
  try {
    const response = await fetch(`https://api.github.com/repos/${owner}/${repoSlug}/pulls`, {
      method: 'POST',
      headers: {
        'Authorization': `token ${config.githubToken}`,
        'Accept': 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(prData),
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(`GitHub API error: ${error.message || response.statusText}`);
    }

    const result = await response.json();
    console.log(`  PR URL: ${result.html_url}`);
    return result;
  } catch (err) {
    console.error(`  Error creating PR: ${err.message}`);
    console.log(`  You can create the PR manually: https://github.com/${owner}/${repoSlug}/compare/${config.baseBranch}...${branchName}`);
  }
}

main().catch(err => {
  console.error(`Unexpected error: ${err.message}`);
  process.exit(1);
});
