#!/usr/bin/env bash
#
# Read-only inventory for an integration batch.
#
# Deliberately does no merging, no verifying and no pushing. Those steps each
# need a judgement call — a conflict worth resolving vs one worth dropping, a
# green CI run that is still valid vs one invalidated by develop moving
# underneath it — and a script that made them silently would be a script that
# merges unverified work at 2am. See SKILL.md for the procedure this feeds.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

case "${1:-candidates}" in

candidates)
    # Ready = open and not a draft. A draft runs no CI by design, which is what
    # makes "push daily" affordable — it is never in the batch.
    echo
    echo "Ready to integrate (open, not draft):"
    echo
    gh pr list --state open --json number,title,isDraft,author,headRefName,updatedAt \
        --jq '[.[] | select(.isDraft | not)]
              | sort_by(.number)
              | .[]
              | "  #\(.number)  \(.headRefName)\n        \(.title)\n        \(.author.login) · updated \(.updatedAt[:10])"' \
        || { echo "  (gh failed — is it authenticated?)"; exit 1; }

    READY=$(gh pr list --state open --json isDraft --jq '[.[] | select(.isDraft | not)] | length')
    DRAFTS=$(gh pr list --state open --json isDraft --jq '[.[] | select(.isDraft)] | length')
    echo
    echo "  $READY ready · $DRAFTS draft (excluded on purpose)"
    [ "$READY" = "0" ] && echo "  Nothing to integrate."
    echo
    ;;

head)
    # The SHA a green CI run is only valid against. Record it before building
    # the integration branch, compare it again before merging — four
    # verification runs have been wasted by develop moving mid-flight.
    git fetch -q origin develop
    git rev-parse origin/develop
    ;;

drafts)
    gh pr list --state open --json number,title,isDraft,author \
        --jq '.[] | select(.isDraft) | "  #\(.number)  \(.title)  [\(.author.login)]"'
    ;;

*)
    echo "usage: batch.sh [candidates|head|drafts]" >&2
    exit 2
    ;;
esac
