<!--
SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
-->

# M3 LLVM ORC — cost-based CPU admission, not unconditional acceleration

The first CPU-only LLVM batch JNI source correctly compiles **one native
kernel per batch** instead of one kernel per array element. This reduces
compilation frequency; it does **not** establish a speedup over Java.

## Executed local measurements, LLVM 19 only

The source-pinned Java 21 test-only LLVM19 loader ran actual LLVM ORC
machine code, using the original JNI long-array copying/compilation path.
The new benchmark deliberately times compilation and disposal per call,
compares all native results with Java semantics, and alternates measurement
order. These numbers are a **diagnostic microbenchmark**, not JMH/production
measurements or conclusions about LLVM24.

Median values from 20 end-to-end samples per operation after separate Java
warmup and eight native warmups:

| Length | Operation | Java (ms) | ORC batch (ms) | ORC / Java |
|---:|---|---:|---:|---:|
| 1,024 | ADD | 0.005 | 3.109 | 670.9x |
| 1,024 | SUBTRACT | 0.002 | 3.189 | 1627.7x |
| 1,024 | MULTIPLY | 0.002 | 2.921 | 1236.3x |
| 65,536 | ADD | 0.083 | 3.487 | 42.1x |
| 65,536 | SUBTRACT | 0.091 | 3.749 | 41.3x |
| 65,536 | MULTIPLY | 0.077 | 3.715 | 48.1x |

The high ratios for tiny arrays are sensitive to timing resolution and
HotSpot optimization. The central finding survives for 65,536-element
batches: **LLVM19 ORC with a new JIT per call was about 40–50x slower than
ordinary Java arrays in this environment**. There is no LLVM24 speedup claim.

## Dispatch rule for M3JDK21

1. **Default primitive ADD/SUBTRACT/MULTIPLY on Java's existing array
   loops and HotSpot**, preserving JDK21 behavior, source/binary compatibility,
   overflow and memory ownership. Do not automatically route these operations
   to per-call ORC merely because the LLVM integration exists.
2. Keep `LlvmOrcBatch` opt-in as an original LLVM correctness/JNI
   qualification path, not a replacement for Java collections or streams.
3. Consider LLVM/MLIR for compute-heavy, reusable plans only **after** a
   separately designed, versioned, bounded ORC-session cache and validated
   JVM/LLVM lifecycle; no persistent native code pointers may leak to Java.
4. Benchmark **LLVM24** and Java with the original source-built native
   library, controlled warmup, varied array lengths, JMH or an equivalent
   properly controlled framework, and measured memory/compile latency
   before any automatic dispatch or performance claim.
5. Retain CPU-first behavior, exact array offset/mask semantics, predictable
   cancellation and no unqualified GPU dependency. Optional LLVM backends
   must not alter observable Java API or behavior.

## Reproduce

After completing the source-built LLVM24 JNI qualification:

```sh
export JAVA_HOME=/path/to/jdk21
export M3_LLVM_LIBRARY=/absolute/path/to/libm3llvmorcjni.so
bash m3/llvm-orc-jni/scripts/benchmark-orc.sh
```

The script compiles only a test-side Java benchmark with the existing
`LlvmOrcJit` and `LlvmOrcBatch`, checks the original JNI admission,
and writes `m3/llvm-orc-jni/build/llvm-orc-latency.csv`. It has **no
pass/fail speed threshold** and does not modify compiler/JNI runtime code.

The complete original LLVM24 build, real LLVM24 ORC arithmetic, JNI
ownership-fault test, original Synexia Java/C source replay and M3JDK21
receiver are **independent gates**. This benchmark is not evidence they
passed. Provenance and licensing remain Apache-2.0 WITH LLVM-exception.
