#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
: "${JAVA_HOME:?JDK 21 JAVA_HOME required}"
mkdir -p build/classes
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all -Werror -d build/classes \
  src/main/java/org/m3jdk/compiler/llvm/LlvmOrcJit.java \
  src/test/java/org/m3jdk/compiler/llvm/LlvmOrcJitWrongMajorTestMain.java
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fPIC -shared \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  tests/wrong_major_stub.c -o build/libm3llvmwrongmajor-test-only.so
"$JAVA_HOME/bin/java" -Xcheck:jni -cp build/classes \
  org.m3jdk.compiler.llvm.LlvmOrcJitWrongMajorTestMain \
  "$PWD/build/libm3llvmwrongmajor-test-only.so"
