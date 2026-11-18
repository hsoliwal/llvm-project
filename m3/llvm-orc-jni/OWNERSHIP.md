<!--
SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
-->

# LLVM ORC transfer-ownership correction (source pinned)

The original LLVM 24 C++ implementation of `LLVMOrcCreateNewThreadSafeContextFromLLVMContext`
constructs `std::unique_ptr<LLVMContext>(unwrap(Ctx))`. Likewise,
`LLVMOrcCreateNewThreadSafeModule` constructs `std::unique_ptr<Module>(unwrap(M))`.
The pointer transfers ownership *during the call*; cleanup must not hold an
independent old owner after a failing return. The previous M3 JNI code cleared
`context` and `module` only after confirming the new ORC wrapper was nonnull.

This patch immediately clears the transferred source pointers before checking
for a null return. Both success paths and the existing Java 21 API are unchanged.
The optional Linux-only test interposes `LLVMOrcCreateNewThreadSafeModule`,
consumes the module exactly like upstream, then simulates wrapper-allocation
failure. The Java test requires `OutOfMemoryError("ORC module allocation failed")`
and successful process exit; double-dispose triggers a fatal native failure.

Run with matching LLVM 24 and Java 21 from the fork:

```sh
cmake -S m3/llvm-orc-jni -B build/m3-llvm-orc-jni -G Ninja \
  -DLLVM_DIR="$PWD/build/llvm/lib/cmake/llvm" \
  -DM3_LLVM_REQUIRED_MAJOR=24 -DM3_LLVM_ENABLE_FAULT_TEST=ON
cmake --build build/m3-llvm-orc-jni
export JAVA_HOME=/path/to/java21
export M3_LLVM_LIBRARY="$PWD/build/m3-llvm-orc-jni/libm3llvmorcjni.so"
export M3_LLVM_TRANSFER_FAULT_LIBRARY="$PWD/build/m3-llvm-orc-jni/libm3llvmtransferfault.so"
bash m3/llvm-orc-jni/scripts/verify-fault.sh
```

The fault interposer is a separate *test-only* shared object; it is never
installed as part of M3JDK21. The real LLVM ORC normal arithmetic/concurrency
smoke must also pass, and the LLVM 24 native CI remains independently required.
Local LLVM 19 source/ABI smoke is not evidence of production LLVM 24 support.

License and provenance: all M3 test/bridge contributions in this folder are
Apache-2.0 WITH LLVM-exception. LLVM's original C++ owner and its versioned C
headers remain under their original notices. The exact C++ source Git blob is
in `SOURCE_PINS.tsv`. This patch makes no changes to the LLVM/JDK original API.
