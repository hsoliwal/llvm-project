/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/** Real bounded Java GC + native ORC batch overlap; no mocked kernels. */
public final class LlvmOrcBatchStressTestMain {
    private LlvmOrcBatchStressTestMain() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1) {
            throw new IllegalArgumentException("one exact native library path required");
        }
        LlvmOrcJit.load(Path.of(args[0]));
        final int workers = 4;
        final int iterations = 25;
        CountDownLatch done = new CountDownLatch(workers);
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Thread collector = new Thread(() -> {
            while (done.getCount() > 0) {
                System.gc();
                Thread.yield();
            }
        }, "m3-batch-gc");
        collector.setDaemon(true);
        collector.start();

        for (int worker = 0; worker < workers; worker++) {
            int id = worker;
            Thread thread = new Thread(() -> {
                try {
                    for (int pass = 0; pass < iterations; pass++) {
                        int length = switch (pass % 5) {
                            case 0 -> 0;
                            case 1 -> 1;
                            case 2 -> 33;
                            case 3 -> 257;
                            default -> 4096;
                        };
                        long[] left = new long[length];
                        long[] right = new long[length];
                        for (int i = 0; i < length; i++) {
                            left[i] = Long.MIN_VALUE + (id * 37L) + i;
                            right[i] = Long.MAX_VALUE - (pass * 31L) - i;
                        }
                        Operation operation = Operation.values()[(id + pass) % 3];
                        long[] results = LlvmOrcBatch.evaluate(operation, left, right);
                        if (results.length != length) {
                            throw new AssertionError("GC stress output length");
                        }
                        for (int i = 0; i < length; i++) {
                            long want = switch (operation) {
                                case ADD -> left[i] + right[i];
                                case SUBTRACT -> left[i] - right[i];
                                case MULTIPLY -> left[i] * right[i];
                            };
                            if (results[i] != want) {
                                throw new AssertionError("GC stress JIT-bit parity");
                            }
                        }
                    }
                } catch (Throwable failure) {
                    problem.compareAndSet(null, failure);
                } finally {
                    done.countDown();
                }
            }, "m3-batch-native-worker-" + id);
            thread.start();
        }
        done.await();
        collector.join(3000L);
        if (problem.get() != null) {
            throw new AssertionError("GC concurrent native ORC batch", problem.get());
        }
        System.out.println("M3_LLVM_ORC_JNI_GC_BATCH_PASS workers=" + workers
                + " calls=" + workers * iterations);
    }
}
