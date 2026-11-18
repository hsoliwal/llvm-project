#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK21 runtime}"
mkdir -p build/classes build/jni-headers
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all -Werror \
  -h build/jni-headers -d build/classes \
  src/main/java/org/m3jdk/compiler/llvm/LlvmOrcJit.java \
  src/test/java/org/m3jdk/compiler/llvm/LlvmOrcJitNoLoadTestMain.java \
  src/test/java/org/m3jdk/compiler/llvm/LlvmOrcJitTestMain.java
"$JAVA_HOME/bin/java" -cp build/classes org.m3jdk.compiler.llvm.LlvmOrcJitNoLoadTestMain
