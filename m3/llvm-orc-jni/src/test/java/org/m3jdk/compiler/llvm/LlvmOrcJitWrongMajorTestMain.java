/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Path;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/** A wrong-major JNI library must poison further native loads in this JVM. */
public final class LlvmOrcJitWrongMajorTestMain {
    private LlvmOrcJitWrongMajorTestMain() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("provide one deliberate wrong-major JNI stub");
        }
        Path wrong = Path.of(args[0]);
        // A rejected path before System.load must not poison later admission.
        try {
            LlvmOrcJit.load(wrong.resolveSibling("missing-before-load.so"));
            throw new AssertionError("missing file was admitted");
        } catch (IllegalArgumentException expected) {
            // A regular file is required before native loading is attempted.
        }
        try {
            LlvmOrcJit.load(wrong);
            throw new AssertionError("wrong LLVM major was admitted");
        } catch (UnsatisfiedLinkError expected) {
            if (!expected.getMessage().contains("expected 24, found 19")) {
                throw new AssertionError("unexpected LLVM JNI mismatch", expected);
            }
        }
        if (LlvmOrcJit.isLoaded()) {
            throw new AssertionError("wrong LLVM native major marked as admitted");
        }
        for (Path candidate : new Path[] {wrong, wrong.resolveSibling("another-library.so")}) {
            try {
                LlvmOrcJit.load(candidate);
                throw new AssertionError("failed native admission was retried");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("previous LLVM JNI load failed")) {
                    throw new AssertionError("wrong failure state", expected);
                }
            }
        }
        try {
            LlvmOrcJit.evaluate(Operation.ADD, 1L, 2L);
            throw new AssertionError("rejected LLVM runtime allowed evaluation");
        } catch (IllegalStateException expected) {
            // No call through the rejected JNI ABI is possible.
        }
        System.out.println("M3_LLVM_JNI_WRONG_MAJOR_FAIL_CLOSED_PASS");
    }
}
