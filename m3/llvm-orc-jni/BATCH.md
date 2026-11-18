<!--
SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
-->

# CPU-only array-backed LLVM ORC batch kernel (DRAFT/HOLD)

**Owner:** original LLVM-C/ORC LLJIT, under the existing
`m3/llvm-orc-jni` integration. The Java type `LlvmOrcBatch` is opt-in,
and requires successful explicit `LlvmOrcJit.load(Path)` first. The existing
scalar API, HotSpot, javac, M3JDK21 compiler, Maven and plugin/lifecycle
interfaces remain unchanged.

A `long[]` pair is an array-backed bulk operation, not one object/JIT per
element. The native C11 function copies two input primitive lanes with
`GetLongArrayRegion` (no Java heap pinning), creates one isolated original
LLVM ORC LLJIT, emits one i64 ADD/SUB/MUL function, verifies the original
module with `LLVMVerifyModule`, resolves one compiled host function and
evaluates all entries. Output is a newly allocated `long[]`. All LLVM
contexts, modules, builder, LLJIT and contiguous temporary buffers are
released by the original-owner cleanup path. No executable address or
native handle is persisted or returned to Java.

The Java and C layers independently enforce **65,536 entries** per call;
mismatched/null inputs and unadmitted libraries refuse before computation.
Empty inputs yield a fresh empty array after library admission. No `nsw`
or `nuw` flags are used; every result is bit-identical to normal Java
`long` wraparound, including `Long.MIN_VALUE` and `Long.MAX_VALUE`.

The native implementation is additive in a second translation unit linked
into the *same* existing `m3llvmorcjni` library. It does not create a
parallel LLVM compiler or load a separate code-generation runtime. Separate
JIT instances per **batch** make this a bounded, source-compatible first
bulk capability; JIT cache/session reuse is not claimed and requires later
ownership/invalidation/performance qualification.

## Executed local evidence (LLVM 19, explicitly test-only loader)

- OpenJDK 21 `javac --release 21 -Xlint:all -Werror`: PASS. The complete
  original `LlvmOrcJit.java` source was reconstructed with Git blob
  `089f769918cf84b3b1243602f3582219f16e273d` and compiled alongside
  the exact new Java batch classes and tests, including generated JNI headers.
- Strict Clang C11 `-Wall -Wextra -Werror -Werror=deprecated-declarations`
  linked the actual JNI batch source against the installed LLVM 19 runtime.
- **69,604 sequential actual ORC native arithmetic comparisons PASS**, plus
  **256 concurrent** result comparisons; includes the exact 65,536-element
  batch, deterministic random values, extreme signed overflow, input
  immutability, fresh output, null/mismatched/oversized refusal.
- An independent **Linux-only** fault interposer consumed the real LLVM19
  module and returned null. The JNI batch error raised the expected
  `OutOfMemoryError`, exited normally, and did not double-dispose.
- The static source-refusal guard
  `scripts/verify-batch.py` passed and rejected six negative mutations
  of limits, module verification, transfer cleanup, heap copying and
  Java arithmetic semantics.

**Do not misinterpret the LLVM19 test loader:** It is not shipped or received;
it only initializes original LLVM19 targets in a separate local harness. The
product Java class `LlvmOrcJit` still pins major 24. The real LLVM24 native
CI compiles the batch source against its generated LLVM24 headers, then runs
Java21/LLVM24 scalar + bulk JIT and scalar + bulk module-consumption faults.

## Qualification and integration policy

The full source-built LLVM24 JIT/native fault tests, LLVM24 sanitizers,
JVM GC stress, Windows/macOS, CPU performance measurements, canonical Synexia
Java/C recipe-first source replay, and **M3JDK21 native compiler reception**
have not passed. This branch is DRAFT/HOLD. The LLVM fork retains original
notices and retains the original C API as its owner; there are no changes
to `llvm/`, `mlir/`, Clang, Java standard libraries, public Maven APIs,
plugin goals or existing JNI entrypoints.

Performance intent is fewer *compilations* per N-element batch, but
there is **no measured performance claim** until native LLVM24 benchmarks
against equivalent Java and scalar ORC paths are executed.
