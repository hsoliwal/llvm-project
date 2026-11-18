#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# In the owning repository this script is under m3/llvm-orc-jni/scripts.
cd "$SCRIPT_DIR/.."
: "${JAVA_HOME:?An actual Java 21 installation is mandatory}"
: "${M3_LLVM_LIBRARY:?Set exact original LLVM-linked JNI library path}"
: "${M3_LLVM_TRANSFER_FAULT_LIBRARY:?Set explicit test-only ELF interposer path}"
for path in "$M3_LLVM_LIBRARY" "$M3_LLVM_TRANSFER_FAULT_LIBRARY"; do
  [[ -f "$path" && ! -L "$path" ]] || { echo 'M3_LLVM_FAULT_LIBRARY_PATH_REFUSAL' >&2; exit 1; }
done
"$SCRIPT_DIR/verify-java.sh"
# This must be a fresh process: LD_PRELOAD is never used with normal acceptance.
# The interposer destroys the module as the upstream std::unique_ptr owner
# does and returns NULL. Any stale JNI module handle will double-free.
output=$(mktemp)
trap 'rm -f "$output"' EXIT
LD_PRELOAD="$M3_LLVM_TRANSFER_FAULT_LIBRARY" \
  "$JAVA_HOME/bin/java" -Xcheck:jni -cp build/classes \
  org.m3jdk.compiler.llvm.LlvmOrcFaultTransferTestMain "$M3_LLVM_LIBRARY" >"$output" 2>&1 || {
    cat "$output" >&2
    echo 'M3_LLVM_ORC_TRANSFER_FAULT_TEST_FAILED' >&2
    exit 1
  }
cat "$output"
grep -Fq 'M3_LLVM_TEST_CONSUMED_MODULE_AND_RETURNED_NULL' "$output"
grep -Fq 'M3_LLVM_ORC_OWNERSHIP_INJECTION_PASS' "$output"
echo 'M3_LLVM_ORC_TRANSFER_FAULT_BOTH_ASSERTIONS_PASS'
