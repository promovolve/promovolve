#!/usr/bin/env bash
# Review and merge only the final, CI-generated pin-back PR after both pin jobs finish.
set -euo pipefail

mode="${1:?usage: approve-pin-back-pr.sh find|approve|merge}"
: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_REPOSITORY_OWNER:?}"
: "${GH_TOKEN:?}"

find_pin_pr() {
  local pulls count files
  pulls=$(gh api "repos/$GITHUB_REPOSITORY/pulls?state=open&head=$GITHUB_REPOSITORY_OWNER:ci/pins&base=main&per_page=100")
  count=$(jq 'length' <<<"$pulls")
  if [ "$count" -eq 0 ]; then
    pin_pr=''
    pin_sha=''
    return
  fi
  [ "$count" -eq 1 ] || { echo "Expected one pin-back PR, found $count" >&2; return 1; }
  jq -e --arg repo "$GITHUB_REPOSITORY" '
    .[0].user.login == "promovolve-pin-back[bot]" and
    .[0].head.ref == "ci/pins" and
    .[0].head.repo.full_name == $repo and
    .[0].base.ref == "main" and
    .[0].base.repo.full_name == $repo
  ' <<<"$pulls" >/dev/null || { echo "Unexpected pin-back PR identity" >&2; return 1; }

  pin_pr=$(jq -r '.[0].number' <<<"$pulls")
  pin_sha=$(jq -r '.[0].head.sha' <<<"$pulls")
  files=$(gh api --paginate "repos/$GITHUB_REPOSITORY/pulls/$pin_pr/files?per_page=100" --jq '.[].filename')
  [ "$files" = 'k8s/kustomization.yaml' ] || { echo "Unexpected files in pin-back PR #$pin_pr" >&2; return 1; }
}

require_stale_dismissal() {
  local rules
  rules=$(gh api "repos/$GITHUB_REPOSITORY/rules/branches/main")
  jq -e '
    [.[] | select(.type == "pull_request" and .parameters.required_approving_review_count > 0)] as $reviews |
    ($reviews | length) > 0 and all($reviews[]; .parameters.dismiss_stale_reviews_on_push == true)
  ' <<<"$rules" >/dev/null \
    || { echo "Main must dismiss stale reviews before automatic approval" >&2; return 1; }
}

case "$mode" in
  find)
    : "${GITHUB_OUTPUT:?}"
    find_pin_pr
    if [ -z "$pin_pr" ]; then
      echo "No pin-back PR to approve"
      exit 0
    fi
    echo "pr=$pin_pr" >>"$GITHUB_OUTPUT"
    echo "sha=$pin_sha" >>"$GITHUB_OUTPUT"
    ;;
  approve)
    : "${PR:?}"
    : "${SHA:?}"
    # Auto-merge may happen after this job exits. The Ruleset must dismiss
    # this approval if a later push changes the reviewed head.
    require_stale_dismissal
    find_pin_pr
    [ "$pin_pr" = "$PR" ] && [ "$pin_sha" = "$SHA" ] \
      || { echo "Pin-back PR changed during review" >&2; exit 1; }
    gh api --method POST "repos/$GITHUB_REPOSITORY/pulls/$PR/reviews" \
      -f event=APPROVE -f commit_id="$SHA" >/dev/null
    ;;
  merge)
    : "${PR:?}"
    : "${SHA:?}"
    find_pin_pr
    [ "$pin_pr" = "$PR" ] && [ "$pin_sha" = "$SHA" ] \
      || { echo "Pin-back PR changed before auto-merge" >&2; exit 1; }
    gh pr merge "$PR" --repo "$GITHUB_REPOSITORY" --auto --squash --match-head-commit "$SHA"
    ;;
  *)
    echo "usage: approve-pin-back-pr.sh find|approve|merge" >&2
    exit 2
    ;;
esac
