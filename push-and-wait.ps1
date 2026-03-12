# Push to main and wait for Docker build workflow. Requires: git, gh (GitHub CLI).
# Usage: .\push-and-wait.ps1 [-Message "commit message"]
# Run from repo root.

param([string]$Message = "Update")

$ErrorActionPreference = "Stop"

Write-Host "=== Staging and committing ===" -ForegroundColor Cyan
git add -A
$status = git status --short
if (-not $status) {
    Write-Host "Nothing to commit."
    exit 0
}
git commit -m $Message
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "`n=== Pushing to main ===" -ForegroundColor Cyan
git push origin main
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Check if Docker workflow was triggered (alexa-downchannel or workflow changed)
$lastCommit = git log -1 --name-only --pretty=format:""
if ($lastCommit -match "alexa-downchannel|docker-publish") {
    Write-Host "`n=== Waiting for Docker build (gh run watch) ===" -ForegroundColor Cyan
    gh run watch --workflow=docker-publish.yml --exit-status
    if ($LASTEXITCODE -eq 0) {
        Write-Host "`nPackage built successfully. Refresh on prod: .\PS\run-with-env.ps1" -ForegroundColor Green
    } else {
        Write-Host "`nBuild failed. Check: https://github.com/gilderman/Amazon-Thermostat/actions" -ForegroundColor Red
        exit $LASTEXITCODE
    }
} else {
    Write-Host "`nPush done. (No Docker workflow triggered for this commit)" -ForegroundColor Yellow
}
