#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

: "${JAVA_HOME:?Point JAVA_HOME to an actual Java 21 installation}"
: "${M3_LLVM_LIBRARY:?Pass the exact source-built LLVM24 JNI shared library}"
if [[ ! -f "$M3_LLVM_LIBRARY" || -L "$M3_LLVM_LIBRARY" ]]; then
  echo "M3_LLVM_COST_NATIVE_LIBRARY_REFUSED" >&2
  exit 1
fi

# Original Java contract and exact benchmark use same JDK21 as the module.
# This is a diagnostic, not a performance gate or unconditional LLVM dispatch.
"$SCRIPT_DIR/verify-java.sh"
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all -Werror \
  -cp build/classes -d build/classes \
  src/test/java/org/m3jdk/compiler/llvm/LlvmOrcBatchCostBenchmarkMain.java

mkdir -p build
output=build/llvm-orc-latency.csv
"$JAVA_HOME/bin/java" -Xcheck:jni -Xms64m -Xmx256m -cp build/classes \
  org.m3jdk.compiler.llvm.LlvmOrcBatchCostBenchmarkMain "$M3_LLVM_LIBRARY" \
  | tee "$output"
grep -Fq "M3_LLVM_BENCH_FINISHED checksum=" "$output"
echo "M3_LLVM_ORC_COST_MEASURED_NO_SPEEDUP_ASSUMED"
