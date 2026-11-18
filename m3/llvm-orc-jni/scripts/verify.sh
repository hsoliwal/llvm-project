#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

: "${JAVA_HOME:?Set JAVA_HOME to the JDK 21 used for this qualification}"
: "${M3_LLVM_LIBRARY:?Set M3_LLVM_LIBRARY to the exact absolute native shared library path}"

"$SCRIPT_DIR/verify-java.sh"
"$JAVA_HOME/bin/java" -Xcheck:jni -cp build/classes org.m3jdk.compiler.llvm.LlvmOrcJitTestMain "$M3_LLVM_LIBRARY"
"$JAVA_HOME/bin/java" -Xcheck:jni -cp build/classes org.m3jdk.compiler.llvm.LlvmOrcBatchTestMain "$M3_LLVM_LIBRARY"
"$JAVA_HOME/bin/java" -Xcheck:jni -Xms32m -Xmx96m -XX:+UseSerialGC -cp build/classes \
  org.m3jdk.compiler.llvm.LlvmOrcBatchStressTestMain "$M3_LLVM_LIBRARY"
