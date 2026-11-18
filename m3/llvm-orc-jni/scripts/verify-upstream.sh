#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
BASE=591f3d1edf0a9ee718d17a0ee70dd013b3c01908

# No silent rebase onto a moved LLVM C ABI: this exact original commit must be
# an ancestor, and every reviewed upstream C ABI blob must be unchanged in HEAD.
git -C "$ROOT" merge-base --is-ancestor "$BASE" HEAD || {
  printf 'M3_LLVM_BASELINE_REFUSAL: exact upstream ancestry unavailable\n' >&2
  exit 1
}
while IFS=$'\t' read -r path blob; do
  [[ -z "$path" || "$path" == \#* ]] && continue
  [[ "$path" =~ ^[A-Za-z0-9_./-]+$ && "$path" != *..* ]] || exit 1
  [[ "$blob" =~ ^[0-9a-f]{40}$ ]] || exit 1
  old=$(git -C "$ROOT" rev-parse --verify "$BASE:$path")
  now=$(git -C "$ROOT" rev-parse --verify "HEAD:$path")
  if [[ "$old" != "$blob" || "$now" != "$blob" ]]; then
    printf 'M3_LLVM_ABI_SOURCE_DRIFT: %s\n' "$path" >&2
    exit 1
  fi
done < "$SCRIPT_DIR/../SOURCE_PINS.tsv"
printf 'M3_LLVM24_UPSTREAM_C_API_BLOBS_PASS\n'
