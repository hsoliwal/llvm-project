/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/** Actual native LLVM ORC array-kernel acceptance; no mocked JIT or IR. */
public final class LlvmOrcBatchTestMain {
    private LlvmOrcBatchTestMain() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1) {
            throw new IllegalArgumentException("exactly one native library path required");
        }
        expect(IllegalStateException.class,
                () -> LlvmOrcBatch.evaluate(Operation.ADD, new long[] {1}, new long[] {2}));
        expect(NullPointerException.class,
                () -> LlvmOrcBatch.evaluate(null, new long[] {1}, new long[] {2}));
        expect(NullPointerException.class,
                () -> LlvmOrcBatch.evaluate(Operation.ADD, null, new long[] {2}));
        expect(NullPointerException.class,
                () -> LlvmOrcBatch.evaluate(Operation.ADD, new long[] {1}, null));
        expect(IllegalArgumentException.class,
                () -> LlvmOrcBatch.evaluate(Operation.ADD, new long[] {1}, new long[0]));
        expect(IllegalArgumentException.class,
                () -> LlvmOrcBatch.evaluate(Operation.ADD,
                        new long[LlvmOrcBatch.MAX_ELEMENTS + 1],
                        new long[LlvmOrcBatch.MAX_ELEMENTS + 1]));

        LlvmOrcJit.load(Path.of(args[0]));
        check(LlvmOrcJit.runtimeMajor() == LlvmOrcJit.LLVM_MAJOR, "LLVM major");
        check(LlvmOrcBatch.evaluate(Operation.ADD, new long[0], new long[0]).length == 0, "empty");

        int checks = 0;
        for (int length : new int[] {1, 2, 8, 64, 257, 1024}) {
            long[] a = new long[length];
            long[] b = new long[length];
            SplittableRandom random = new SplittableRandom(0x3f42_2026L + length);
            for (int i = 0; i < length; i++) {
                a[i] = random.nextLong();
                b[i] = random.nextLong();
            }
            a[0] = Long.MIN_VALUE;
            b[0] = -1L;
            if (length > 1) {
                a[1] = Long.MAX_VALUE;
                b[1] = 1L;
            }
            long[] savedA = a.clone();
            long[] savedB = b.clone();
            for (Operation op : Operation.values()) {
                long[] result = LlvmOrcBatch.evaluate(op, a, b);
                check(result != a && result != b, "fresh output");
                check(result.length == length, "output length");
                for (int i = 0; i < length; i++) {
                    long expected = switch (op) {
                        case ADD -> a[i] + b[i];
                        case SUBTRACT -> a[i] - b[i];
                        case MULTIPLY -> a[i] * b[i];
                    };
                    check(result[i] == expected, "bitwise Java overflow parity");
                    checks++;
                }
            }
            check(Arrays.equals(savedA, a) && Arrays.equals(savedB, b), "inputs unchanged");
        }

        long[] maximumLeft = new long[LlvmOrcBatch.MAX_ELEMENTS];
        long[] maximumRight = new long[LlvmOrcBatch.MAX_ELEMENTS];
        for (int i = 0; i < maximumLeft.length; i++) {
            maximumLeft[i] = Long.MAX_VALUE - i;
            maximumRight[i] = Long.MIN_VALUE + i;
        }
        long[] maximumResult = LlvmOrcBatch.evaluate(Operation.ADD, maximumLeft, maximumRight);
        check(maximumResult.length == LlvmOrcBatch.MAX_ELEMENTS, "maximum batch length");
        for (int i = 0; i < maximumResult.length; i++) {
            check(maximumResult[i] == -1L, "maximum batch overflow");
            checks++;
        }

        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int worker = 0; worker < 2; worker++) {
            int id = worker;
            Thread thread = new Thread(() -> {
                try {
                    long[] left = new long[128];
                    long[] right = new long[128];
                    for (int j = 0; j < 128; j++) {
                        left[j] = Long.MIN_VALUE + (j * 17L) + id;
                        right[j] = Long.MAX_VALUE - (j * 19L);
                    }
                    long[] result = LlvmOrcBatch.evaluate(Operation.MULTIPLY, left, right);
                    for (int j = 0; j < 128; j++) {
                        check(result[j] == left[j] * right[j], "concurrent output");
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "m3-orc-batch-" + id);
            thread.start();
        }
        done.await();
        if (error.get() != null) {
            throw new AssertionError("concurrent ORC array kernels", error.get());
        }
        System.out.println("M3_LLVM_ORC_JNI_REAL_BATCH_PASS major=" + LlvmOrcJit.runtimeMajor()
                + " checked=" + checks + " concurrent-elements=256");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (type.isInstance(actual)) {
                return;
            }
            throw new AssertionError("wrong exception", actual);
        }
        throw new AssertionError("expected " + type.getName());
    }
}
