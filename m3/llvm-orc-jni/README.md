<!--
SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
-->

# M3JDK21 / LLVM 24 — CPU ORC JIT JNI admission

This **additive, opt-in** LLVM fork example exercises the existing LLVM C ABI
and ORC LLJIT owner. It is *not* a second LLVM compiler, a new HotSpot JIT,
a Maven plugin, a replacement for `javac`, or a new M3JDK21 product module.
It does not change any LLVM, MLIR, clang, or linker's existing source or
lifecycle. The native ownership and code-generation responsibilities remain
inside LLVM.

## Contract

* Java 21, package `org.m3jdk.compiler.llvm`, implements a bounded experimental
  `LlvmOrcJit` with explicit native loading and three arithmetic operations.
  There is no implicit native-library lookup, download, arbitrary IR ingestion,
  plugin execution, or user-provided machine code. Calls are CPU-only.
* Native C11/JNI uses LLVM's original **LLVM-C + ORC LLJIT** APIs. Each
  invocation creates a new LLVM context and module, constructs one i64 binary
  function, checks it with the original `LLVMVerifyModule`, transfers
  ownership to an ORC `ThreadSafeModule`, adds it to LLJIT, looks up the
  JIT-compiled symbol and executes it in the current process. All temporary
  ORC/LLVM objects are disposed at the original-owner boundaries.
* `add`, `subtract` and `multiply` deliberately exclude `nsw`/`nuw`
  flags: the i64 result has Java `long` modulo-2^64 semantics, including
  overflow. The native result's bit pattern is copied into JNI `jlong`.
* JIT code never escapes to Java as an address or executable handle. The
  library must be explicitly loaded from a **regular, non-symlink native file**.
* The production Java binding enforces **LLVM major 24**. The native initializer
  checks the loaded runtime major against its own compile-time LLVM headers;
  incompatible libraries fail closed. The LLVM ORC C ABI is experimental,
  so both native and Java versions are source-pinned.

The source-pin script `scripts/verify-upstream.sh` requires the exact
original LLVM baseline to be an ancestor and checks each reviewed LLVM C API
Git blob at both the pinned baseline and receiving commit. It refuses
changed headers or an unrelated fork history before native compilation.

This first acceptance slice deliberately creates one JIT per operation. It
is a correctness/interop probe, **not a claimed fast path**. Reuse of ORC
execution sessions, caching, vector/MLIR lowering, GPU backends and persistent
object ownership require separate lifetime, instrumentation and performance
qualifications before M3JDK21 integration.

## Source-pinned build (LLVM 24)

The exact LLVM baseline is `591f3d1edf0a9ee718d17a0ee70dd013b3c01908`
(LLVM `main`, 18 November 2026). The native library is *never* built against
an unverified user DLL in an M3JDK distribution.

```sh
# From the llvm-project repository root, with CMake, Ninja and JDK 21 installed.
cmake -S llvm -B build/llvm -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLVM_TARGETS_TO_BUILD=X86 \
  -DLLVM_BUILD_LLVM_DYLIB=ON -DLLVM_LINK_LLVM_DYLIB=ON \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF
cmake --build build/llvm --target LLVM -j 2
cmake -S m3/llvm-orc-jni -B build/m3-llvm-jni -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DM3_LLVM_REQUIRED_MAJOR=24 \
  -DLLVM_DIR="$PWD/build/llvm/lib/cmake/llvm"
cmake --build build/m3-llvm-jni -j 2
export JAVA_HOME="/path/to/your/JDK21"
export M3_LLVM_LIBRARY="$PWD/build/m3-llvm-jni/libm3llvmorcjni.so"
bash m3/llvm-orc-jni/scripts/verify.sh
```

On Windows and macOS, use the platform's JNI-capable LLVM 24 build and library
extension, and supply the absolute path explicitly. Those platforms are **not
qualified** by this Linux-only native CI slice. `verify-java.sh` separately
runs the Java-only no-native/refusal tests without LLVM installed.

The CMake project refuses LLVM other than the explicitly selected major and
requires a 64-bit host. A JNI library built against LLVM 24 headers must be
linked and tested with the matching LLVM 24 runtime. The Java API also refuses
other majors. The C source includes a pre-LLVM24 compatibility branch solely
for compiling a local LLVM19 host smoke with its earlier ORC context-getter
API; this does **not** relax production LLVM24 admission.

## Test evidence and release boundary

Run `JAVA_HOME=/path/to/jdk21 bash m3/llvm-orc-jni/scripts/verify-java.sh`
for source-compatible Java21 compilation with `--release 21 -Xlint:all -Werror`
and no-native fail-closed behavior.

The included JDK21 `LlvmOrcJitTestMain` is not an assertion-only model: it
requires the actual native shared library, executes `LLVMVerifyModule` and
ORC JIT for 24 arithmetic samples across 3 operations, plus 6 concurrent
operations. It checks Java-long overflow bit-for-bit, version admission,
source-selected loading, and no-library behavior.

**Qualification state at creation**: Java21 compile and no-native refusal
passed locally. An explicitly marked LLVM19 **test-only** temporary binding
executed the same JIT path successfully using the installed LLVM19 runtime;
the unmodified Java24 binding correctly refused LLVM19. The native LLVM24
source build and its own CI, Windows/macOS, runtime JNI/GC safety and full
M3JDK21 receiver have not passed and remain `HOLD`. No speedup or production
backend availability is claimed.

## Ownership and licensing

This original example is Apache-2.0 WITH LLVM-exception in the LLVM fork;
LLVM sources remain owned by the LLVM project under their existing notices.
Any future received M3JDK21 source must preserve its source history,
Apache/LLVM exception, JNI symbol ABI, original SDK ownership and the exact
LLVM24 native binary provenance. New source candidates require Synexia's
canonical recipe-first source custody and independently passing JDK tests.
See `SOURCE_PINS.tsv` and `QUALIFICATION.json`.
