/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/**
 * Honest end-to-end native-vs-ordinary-Java latency probe. This includes
 * ORC compilation, JNI array copying, allocation, and cleanup per invocation.
 * There is no JNI session reuse and no claimed production LLVM24 speedup.
 */
public final class LlvmOrcBatchCostBenchmarkMain {
    private static volatile long checksum;
    private LlvmOrcBatchCostBenchmarkMain() {}

    private static long[] javaEvaluate(long[] a, long[] b, Operation operation) {
        long[] output = new long[a.length];
        for (int i = 0; i < output.length; i++) {
            output[i] = switch (operation) {
                case ADD -> a[i] + b[i];
                case SUBTRACT -> a[i] - b[i];
                case MULTIPLY -> a[i] * b[i];
            };
        }
        return output;
    }

    private static long[] nativeEvaluate(long[] a, long[] b, Operation operation) {
        return LlvmOrcBatch.evaluate(operation, a, b);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void check(long[] expected, long[] result) {
        if (!Arrays.equals(expected, result)) {
            throw new AssertionError("differential Java/ORC mismatch");
        }
        checksum ^= result[result.length - 1];
    }

    private static void benchSize(int size, int samples, int warmups) {
        long[] a = new long[size], b = new long[size];
        SplittableRandom random = new SplittableRandom(size ^ 0x2026_1118_55L);
        for (int i = 0; i < size; i++) {
            a[i] = random.nextLong();
            b[i] = random.nextLong();
        }
        a[0] = Long.MIN_VALUE;
        b[0] = Long.MAX_VALUE;

        for (Operation op : Operation.values()) {
            long[] expected = javaEvaluate(a, b, op);
            for (int k = 0; k < warmups; k++) {
                check(expected, nativeEvaluate(a, b, op));
                check(expected, javaEvaluate(a, b, op));
            }
            // Warm HotSpot's direct Java implementation separately; source result
            // is observed by checksum to avoid dead-code elimination.
            int javaWarmups = size < 1024 ? 10000 : (size < 65536 ? 4000 : 300);
            for (int j = 0; j < javaWarmups; j++) {
                check(expected, javaEvaluate(a, b, op));
            }
            long[] directNs = new long[samples];
            long[] orcNs = new long[samples];
            for (int i = 0; i < samples; i++) {
                // Alternate ordering to reduce systematic first-measure bias.
                if ((i & 1) == 0) {
                    long start = System.nanoTime();
                    long[] result = javaEvaluate(a, b, op);
                    directNs[i] = System.nanoTime() - start;
                    check(expected, result);
                    start = System.nanoTime();
                    result = nativeEvaluate(a, b, op);
                    orcNs[i] = System.nanoTime() - start;
                    check(expected, result);
                } else {
                    long start = System.nanoTime();
                    long[] result = nativeEvaluate(a, b, op);
                    orcNs[i] = System.nanoTime() - start;
                    check(expected, result);
                    start = System.nanoTime();
                    result = javaEvaluate(a, b, op);
                    directNs[i] = System.nanoTime() - start;
                    check(expected, result);
                }
            }
            long javaMedian = median(directNs), nativeMedian = median(orcNs);
            System.out.printf("%s,%d,%d,%d,%.3f,%.3f,%.1f%n", op, size, samples, warmups,
                    javaMedian / 1_000_000.0, nativeMedian / 1_000_000.0,
                    (double) nativeMedian / Math.max(javaMedian, 1L));
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("exact native lib path required");
        LlvmOrcJit.load(Path.of(args[0]));
        if (LlvmOrcJit.runtimeMajor() != LlvmOrcJit.LLVM_MAJOR) {
            throw new AssertionError("native major pin violation");
        }
        System.out.println("M3_LLVM_BENCH_META,major=" + LlvmOrcJit.runtimeMajor() + ",vm="
                + System.getProperty("java.version") + ",arch=" + System.getProperty("os.arch"));
        System.out.println("operation,batch_size,samples,warmups,java_median_ms,orc_median_ms,orc_to_java_ratio");
        for (int size : new int[] {1024, 65536}) {
            benchSize(size, 20, 8);
        }
        System.out.println("M3_LLVM_BENCH_FINISHED checksum=" + Long.toUnsignedString(checksum));
    }
}
