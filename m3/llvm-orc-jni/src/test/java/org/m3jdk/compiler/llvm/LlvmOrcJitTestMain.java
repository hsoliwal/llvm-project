/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/** Native JDK21 acceptance; only PASS when an actual LLVM ORC library ran. */
public final class LlvmOrcJitTestMain {
    private LlvmOrcJitTestMain() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1) {
            throw new IllegalArgumentException("one explicit native shared library path is required");
        }
        if (LlvmOrcJit.isLoaded()) {
            throw new AssertionError("native code must be opt-in");
        }
        expect(IllegalStateException.class, () -> LlvmOrcJit.evaluate(Operation.ADD, 1, 2));
        expect(NullPointerException.class, () -> LlvmOrcJit.evaluate(null, 1, 2));
        LlvmOrcJit.load(Path.of(args[0]));
        LlvmOrcJit.load(Path.of(args[0])); // same path is idempotent
        check(LlvmOrcJit.runtimeMajor() == LlvmOrcJit.LLVM_MAJOR, "runtime version");
        expect(IllegalStateException.class, () -> LlvmOrcJit.load(Path.of(args[0] + ".foreign")));

        long[][] samples = {
            {0, 0}, {1, 2}, {-7, 4}, {100, -100},
            {Long.MIN_VALUE, -1}, {Long.MAX_VALUE, 1},
            {Long.MIN_VALUE, Long.MAX_VALUE}, {123456789123456789L, 987654321L}
        };
        for (long[] pair : samples) {
            long a = pair[0], b = pair[1];
            check(LlvmOrcJit.evaluate(Operation.ADD, a, b) == a + b, "ADD bits");
            check(LlvmOrcJit.evaluate(Operation.SUBTRACT, a, b) == a - b, "SUBTRACT bits");
            check(LlvmOrcJit.evaluate(Operation.MULTIPLY, a, b) == a * b, "MULTIPLY bits");
        }
        final CountDownLatch done = new CountDownLatch(2);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int task = 0; task < 2; task++) {
            int id = task;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        long a = ((long) id << 45) | (i * 1231L);
                        long b = Long.MIN_VALUE + i;
                        check(LlvmOrcJit.evaluate(Operation.ADD, a, b) == a + b, "threaded ADD");
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    done.countDown();
                }
            }, "m3-llvm-orc-" + task);
            thread.start();
        }
        done.await();
        if (failure.get() != null) {
            throw new AssertionError("parallel isolated ORC JIT", failure.get());
        }
        System.out.println("M3_LLVM_ORC_JNI_REAL_NATIVE_PASS major=" + LlvmOrcJit.runtimeMajor()
                + " samples=" + samples.length + " operations=3 parallel=2");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("expected " + expected.getName(), actual);
        }
        throw new AssertionError("expected " + expected.getName());
    }
}
