/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Path;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/** Opt-in ELF/Linux consuming-module fault test, in a fresh JVM. */
public final class LlvmOrcBatchFaultTestMain {
    private LlvmOrcBatchFaultTestMain() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("one explicit native JNI library required");
        }
        LlvmOrcJit.load(Path.of(args[0]));
        try {
            LlvmOrcBatch.evaluate(Operation.ADD, new long[] {Long.MIN_VALUE}, new long[] {1L});
            throw new AssertionError("expected injected ORC module-consumption error");
        } catch (OutOfMemoryError expected) {
            if (!"LLVM batch thread module failed".equals(expected.getMessage())) {
                throw new AssertionError("unexpected injected native failure", expected);
            }
        }
        System.out.println("M3_LLVM_ORC_BATCH_CONSUMED_MODULE_FAULT_PASS");
    }
}
