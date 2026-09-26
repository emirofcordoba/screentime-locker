#!/usr/bin/env bash
# =============================================================================
#  Screen Time Locker — one-click push to GitHub
# =============================================================================
#  Commits the current working tree and pushes it straight to GitHub, using a
#  personal access token. No prompts, no interactive git, single command.
#
#  Usage:
#      ./push-to-github.sh                       # auto commit message
#      ./push-to-github.sh "Release 7.2: ..."    # custom commit message
#      BRANCH=dev ./push-to-github.sh "wip"      # push another branch
#
#  The token is looked up in this order (first hit wins):
#      1. $GITHUB_TOKEN            (or $GH_TOKEN) in the environment
#      2. ~/.screentime-locker-token
#      3. ./.github-token          (git-ignored, never committed)
#
#  The token is never written to .git/config: each push injects it as a
#  one-shot HTTP Authorization header, so it never lands on disk in this repo.
#
#  Environment overrides:
#      REPO_SLUG   owner/repo   (default emirofcordoba/screentime-locker)
#      BRANCH      branch name  (default main)
# =============================================================================
set -euo pipefail

# ----- locate the repo -------------------------------------------------------
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

REPO_SLUG="${REPO_SLUG:-emirofcordoba/screentime-locker}"
BRANCH="${BRANCH:-main}"

log() { printf '\033[1;36m[push]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[push] error:\033[0m %s\n' "$*" >&2; exit 1; }

# ----- resolve the token -----------------------------------------------------
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
TOKEN_SOURCE="environment"
if [ -z "$TOKEN" ]; then
  for f in "$HOME/.screentime-locker-token" "$REPO_DIR/.github-token"; do
    if [ -f "$f" ]; then
      TOKEN="$(tr -d ' \t\r\n' < "$f")"
      TOKEN_SOURCE="$f"
      break
    fi
  done
fi
[ -n "$TOKEN" ] || die "no GitHub token found. Set GITHUB_TOKEN, or write one to ~/.screentime-locker-token"

# Sanity: make sure this is a git repo with an origin.
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not a git repository: $REPO_DIR"

# ----- make sure a commit identity exists ------------------------------------
if ! git config user.name  >/dev/null 2>&1; then git config user.name  "Emir of Cordoba"; fi
if ! git config user.email >/dev/null 2>&1; then git config user.email "159137813+emirofcordoba@users.noreply.github.com"; fi

# ----- commit any pending work ----------------------------------------------
MSG="${1:-Update Screen Time Locker ($(date '+%Y-%m-%d %H:%M'))}"

git add -A
if git diff --cached --quiet; then
  log "nothing to commit — working tree already clean"
else
  log "committing: $MSG"
  git commit -m "$MSG" >/dev/null
fi

# ----- push with a one-shot, token-scoped auth header ------------------------
log "pushing HEAD to $REPO_SLUG:$BRANCH (token from $TOKEN_SOURCE)"
AUTH_B64="$(printf 'x-access-token:%s' "$TOKEN" | base64 | tr -d '\n')"

if git -c http.extraheader="Authorization: Basic ${AUTH_B64}" \
       push "https://github.com/${REPO_SLUG}.git" "HEAD:${BRANCH}" 2>&1 | sed 's/^/[push] /'; then
  log "done — $REPO_SLUG@$BRANCH is up to date"
else
  die "push failed. Check the token's 'repo' scope and the branch name."
fi
