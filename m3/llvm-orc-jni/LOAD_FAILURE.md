<!--
SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
-->

# JNI failed-load admission — opt-in CPU LLVM 24 boundary

The original Java 21 `LlvmOrcJit` requires LLVM major 24.
`System.load` can register JNI symbols before `nativeInitialize` rejects a
wrong-major library. Retrying with another library within the *same JVM*
could mix incompatible JNI implementations.

The additive private fail-closed state `rejectedNativeLoad` prevents all
subsequent `load(Path)` attempts after a native load or initialization fails.
Before any native load is attempted, regular-file and null argument validation
preserve their existing behavior. A successfully admitted library still loads
idempotently at the same path and refuses a different path.

## Executable no-LLVM regression

`bash m3/llvm-orc-jni/scripts/verify-wrong-major.sh`

The opt-in Linux test builds `tests/wrong_major_stub.c`, whose only JNI entry
point returns major 19. It then launches a **fresh Java 21 JVM** and verifies:
an invalid file path is rejected before native loading; the deliberately wrong
major is rejected with the correct version diagnostic; `isLoaded()` remains
false; later attempts to load either the same or a different native file are
rejected without invoking JNI; and evaluating while rejected is impossible.

This deliberately small stub is **test-only**, not an LLVM implementation,
distribution artifact, production native dependency or machine-code backend.
It remains out of the original CMake native library and is not loaded by the
normal LLVM ORC arithmetic acceptance job.

## Qualification and lifecycle boundary

- Locally executed: JDK 21 `javac --release 21 -Xlint:all -Werror`;
  C11 `cc -Wall -Wextra -Werror -shared -fPIC`;
  Java `-Xcheck:jni` test: PASS.
- Production Java constant remains `LLVM_MAJOR=24`; API/JNI native method
  names and signatures, original LLVM/Clang/MLIR code, and Maven goals unchanged.
- Original source-built LLVM24 native ORC, concurrency, ownership fault
  injection, canonical Synexia SDK, M3JDK21 runtime receiving, and
  cross-platform acceptance remain separate qualification gates.

**HOLD:** A passing wrong-major stub is not proof that LLVM24 runs correctly.
