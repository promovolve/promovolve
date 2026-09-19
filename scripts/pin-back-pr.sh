#!/usr/bin/env bash
#
# Land a CI-generated pin update on `main` THROUGH A PULL REQUEST.
#
# WHY THIS EXISTS: deploy.yml's pin-images and publish-banner jobs used to
# commit k8s/kustomization.yaml and `git push origin HEAD:main`. The `main`
# Ruleset requires a pull request and green status checks, so a rollout
# would SUCCEED and then the pin-back step failed with GH013 (2026-08-30,
# three deploys in a row; GH issue #33). This script keeps the Ruleset
# intact — no bypass for github-actions[bot] — and still gets the pins
# onto main without a human in the loop:
#
#   1. check out the shared pin branch (ci/pins), created from main when
#      absent; merge main into it if it has fallen behind
#   2. run the caller's pin command (scripts/pin-image-digest.sh or
#      scripts/pin-banner-url.sh) against the working tree
#   3. commit k8s/kustomization.yaml and push the branch — on rejection
#      re-fetch and re-run the pin command rather than rebasing a commit,
#      so a concurrent pin from the OTHER job is never overwritten
#   4. open the PR if none is open for the branch and enable auto-merge
#      (squash) so it lands as soon as the required checks are green
#
# THE PUSH MUST NOT USE GITHUB_TOKEN. A push made with the workflow token
# triggers no workflows, and check runs from a `workflow_dispatch` run do
# NOT count toward a pull request's required status checks (verified
# 2026-09-04: six green check runs on the head commit, statusCheckRollup
# null, PR #41 BLOCKED forever). The pin branch is therefore pushed over
# SSH with a write deploy key (secret PIN_DEPLOY_KEY, created by
# scripts/setup-pin-deploy-key.sh; deploy.yml hands it to actions/checkout
# as `ssh-key`), so the push fires ci.yml's `push` trigger on `ci/pins` and
# those check runs are the ones the Ruleset counts. `gh` keeps using
# GITHUB_TOKEN for the PR itself — the "Actions may create pull requests"
# setting covers that, and auto-merge needs no extra identity.
#
# ONE branch, ONE PR: digest and banner pins from the same deploy share it,
# a rerun finds it already open, and delete-branch-on-merge retires it so
# the next deploy starts fresh from main. The squash commit it lands on
# main touches only k8s/kustomization.yaml, which deploy.yml's push
# trigger ignores (`paths-ignore`), so a pin merge never burns a Deploy.
# No `[skip ci]` anywhere: it would also skip the CI run on the branch.
#
#   scripts/pin-back-pr.sh "<commit subject>" "<commit body>" <pin-command...>
#
# Requires: gh (authenticated with GITHUB_TOKEN), a checkout whose origin
# pushes over SSH with the deploy key, and the calling job to hold
# `contents: write` and `pull-requests: write`. Repository settings: "Allow
# GitHub Actions to create and approve pull requests" and "Allow auto-merge"
# must be on.
#
# Idempotent: with nothing to pin it exits 0 without a commit, but still
# makes sure an open PR (from an earlier job) has auto-merge armed.
set -euo pipefail

SUBJECT="${1:?usage: pin-back-pr.sh <commit subject> <commit body> <pin-command...>}"
BODY="${2:?missing commit body}"
shift 2
[ $# -ge 1 ] || { echo "missing pin command" >&2; exit 2; }

BRANCH="${PIN_BRANCH:-ci/pins}"
BASE="${PIN_BASE:-main}"
FILE="k8s/kustomization.yaml"
PR_TITLE="ci: pin deployed digests / banner url"

cd "$(git rev-parse --show-toplevel)"
case "$(git remote get-url --push origin)" in
  git@github.com:*|ssh://*) ;;
  *) echo "WARNING: origin pushes over HTTPS (GITHUB_TOKEN) — that push triggers no CI run, so the" >&2
     echo "         pin PR will never satisfy its required checks. Is secret PIN_DEPLOY_KEY set?" >&2
     echo "         (scripts/setup-pin-deploy-key.sh creates it.)" >&2 ;;
esac
git config user.name  "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

# --- 1–3. pin, commit, push (retry on a concurrent push) -----------------------
pushed=0
for attempt in 1 2 3 4 5; do
  git fetch -q origin "$BASE"
  # Explicit refspec so a branch deleted remotely mid-loop cannot linger as a
  # stale origin/ ref from the initial checkout.
  remote_has_branch=0
  git fetch -q origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH" 2>/dev/null && remote_has_branch=1
  if [ "$remote_has_branch" -eq 1 ] \
     && [ -z "$(gh pr list --head "$BRANCH" --base "$BASE" --state open --json number --jq '.[0].number // empty')" ] \
     && git diff --quiet "origin/$BASE" "origin/$BRANCH" -- "$FILE"; then
    # A leftover from a PR that already squash-merged: no PR is open and the
    # file matches main byte for byte, so nothing on it is unlanded. GitHub's
    # delete-branch-on-merge did not fire for the bot's auto-merge (PR #41,
    # 2026-09-04), and building on the leftover would drag every old commit
    # into the next PR. Start fresh from main. The content check is what
    # makes this safe against a sibling job that pushed seconds ago and has
    # not opened its PR yet: its pin changes the file, so we leave it alone.
    echo "$BRANCH is a merged leftover — deleting it and starting from $BASE"
    git push -q origin --delete "$BRANCH" || true
    git checkout -q -B "$BRANCH" "origin/$BASE"
  elif [ "$remote_has_branch" -eq 1 ]; then
    git checkout -q -B "$BRANCH" "origin/$BRANCH"
    # Keep the pin branch on top of main so its CI run reflects current
    # code. Squash-merge makes the merge commit vanish on landing. A conflict
    # means someone hand-edited a CI-owned line on main; the file says not
    # to, so fail loudly rather than guess.
    if ! git merge-base --is-ancestor "origin/$BASE" HEAD; then
      git merge -q --no-edit "origin/$BASE" \
        || { git merge --abort; echo "merge of $BASE into $BRANCH conflicts — resolve by hand" >&2; exit 1; }
    fi
  else
    git checkout -q -B "$BRANCH" "origin/$BASE"
  fi

  "$@"

  if git diff --quiet -- "$FILE"; then
    echo "pins already current on $BRANCH — nothing to commit"
    pushed=1; break
  fi
  git add "$FILE"
  git commit -q -m "$SUBJECT" -m "$BODY"
  if git push -q origin "HEAD:refs/heads/$BRANCH"; then
    pushed=1; break
  fi
  # Someone (the sibling pin job, most likely) pushed first. Drop our commit
  # and re-apply the pin on top of theirs — re-running the pin command is
  # the merge strategy, so neither change can be lost.
  echo "push rejected (attempt $attempt) — re-applying on the latest $BRANCH"
  git reset -q --hard "HEAD~1"
done
[ "$pushed" -eq 1 ] || { echo "could not push $BRANCH after 5 attempts" >&2; exit 1; }

# If the branch carries nothing beyond main there is no PR to open — this is
# the "already pinned, rerun" case with no earlier job having pushed either.
if git merge-base --is-ancestor HEAD "origin/$BASE"; then
  echo "$BRANCH has no changes against $BASE — no pull request needed"
  exit 0
fi

# --- 4. PR + auto-merge --------------------------------------------------------
pr=$(gh pr list --head "$BRANCH" --base "$BASE" --state open --json number --jq '.[0].number // empty')
if [ -z "$pr" ]; then
  pr=$(gh pr create --base "$BASE" --head "$BRANCH" \
        --title "$PR_TITLE" \
        --body "$(printf '%s\n\n%s\n\n%s' \
          "Generated by deploy.yml — writes what the cluster is actually running back into \`k8s/kustomization.yaml\` so a manual \`k8s-gke/setup.sh --deploy-only\` cannot roll it backwards." \
          "Auto-merges once CI is green. Each commit names the Deploy run that produced it." \
          "Do not hand-edit; if it conflicts, close it and the next deploy opens a fresh one.")")
  pr="${pr##*/}"
  echo "opened pull request #$pr"
else
  echo "pull request #$pr already open for $BRANCH"
  # A multi-commit PR squashes under its TITLE, so the title must stay clean:
  # PR #41 was opened by an earlier version with a skip-ci marker in it, which
  # would have landed on main and starved the next deploy's ci-gate.
  if [ "$(gh pr view "$pr" --json title --jq .title)" != "$PR_TITLE" ]; then
    gh pr edit "$pr" --title "$PR_TITLE" >/dev/null && echo "retitled #$pr"
  fi
fi

# The push above (deploy key, not GITHUB_TOKEN) fired ci.yml on the branch —
# as a `push` run and, once the PR exists, a `pull_request` run too; both
# are cheap and the first push of a fresh branch has only the former, which
# is why ci.yml keeps ci/pins in its push branches. Nothing to dispatch.

# Squash is the only merge method the Ruleset allows. Auto-merge waits for
# the required checks; a rerun that finds it already enabled is a no-op.
gh pr merge "$pr" --auto --squash \
  || echo "auto-merge could not be enabled on #$pr — merge it by hand once CI is green" >&2
echo "pull request: $(gh pr view "$pr" --json url --jq .url)"
