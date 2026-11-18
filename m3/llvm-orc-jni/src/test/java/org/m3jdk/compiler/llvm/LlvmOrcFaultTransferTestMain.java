/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Path;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/** Linux test-only double-dispose regression: actual LLVM owns the module. */
public final class LlvmOrcFaultTransferTestMain {
    private LlvmOrcFaultTransferTestMain() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("exactly one trusted native JNI library path required");
        }
        LlvmOrcJit.load(Path.of(args[0]));
        try {
            LlvmOrcJit.evaluate(Operation.ADD, Long.MIN_VALUE, 1);
            throw new AssertionError("expected injected LLVM module allocation failure");
        } catch (OutOfMemoryError expected) {
            if (!"ORC module allocation failed".equals(expected.getMessage())) {
                throw new AssertionError("unexpected native fault", expected);
            }
        }
        System.out.println("M3_LLVM_ORC_OWNERSHIP_INJECTION_PASS");
    }
}
