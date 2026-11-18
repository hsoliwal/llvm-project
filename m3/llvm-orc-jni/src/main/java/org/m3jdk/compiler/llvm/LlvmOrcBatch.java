/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.util.Objects;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/**
 * Bounded, array-backed CPU-only LLVM ORC bulk arithmetic.
 *
 * <p>Each call compiles exactly one verified LLVM i64 operation and evaluates
 * all input pairs with that native function. Unlike the scalar JIT proof, this
 * avoids recompiling the same LLVM function for every array element. It does
 * not retain executable addresses, native contexts, or JIT handles between
 * calls. The caller must first admit the same pinned LLVM library through
 * {@link LlvmOrcJit#load(java.nio.file.Path)}.
 *
 * <p>Input arrays are not modified. The result is a fresh {@code long[]} with
 * ordinary Java wraparound arithmetic, including signed overflow. There is no
 * implicit download, library discovery or arbitrary LLVM-IR input.
 */
public final class LlvmOrcBatch {
    /** Maximum accepted array elements per call; not a performance promise. */
    public static final int MAX_ELEMENTS = 65_536;

    private LlvmOrcBatch() {}

    /**
     * Compute one pure binary operation over two equally sized long arrays.
     *
     * @param operation exactly one of ADD, SUBTRACT, MULTIPLY
     * @param left left-hand operands; never modified
     * @param right right-hand operands; never modified
     * @return a new array of results; empty for empty inputs
     * @throws NullPointerException for null arguments
     * @throws IllegalArgumentException for unequal lengths or over-budget input
     * @throws IllegalStateException when the pinned LLVM JNI library is not loaded
     */
    public static long[] evaluate(Operation operation, long[] left, long[] right) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.length != right.length) {
            throw new IllegalArgumentException("LLVM batch operand lengths must match");
        }
        if (left.length > MAX_ELEMENTS) {
            throw new IllegalArgumentException("LLVM batch observation budget exceeded");
        }
        if (!LlvmOrcJit.isLoaded()) {
            throw new IllegalStateException("LLVM JNI is not loaded; call LlvmOrcJit.load(Path) explicitly");
        }
        if (left.length == 0) {
            return new long[0];
        }
        return nativeEvaluateBatch(operation.ordinal(), left, right);
    }

    private static native long[] nativeEvaluateBatch(int opcode, long[] left, long[] right);
}
