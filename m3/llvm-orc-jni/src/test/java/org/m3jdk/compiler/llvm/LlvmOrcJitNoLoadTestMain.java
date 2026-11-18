/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.m3jdk.compiler.llvm.LlvmOrcJit.Operation;

/** Java-only fail-closed proof; it deliberately never loads a native library. */
public final class LlvmOrcJitNoLoadTestMain {
    private LlvmOrcJitNoLoadTestMain() {}

    public static void main(String[] args) throws IOException {
        if (LlvmOrcJit.isLoaded()) {
            throw new AssertionError("native library loaded without explicit selection");
        }
        expect(IllegalStateException.class, () -> LlvmOrcJit.evaluate(Operation.ADD, 1, 2));
        expect(IllegalStateException.class, LlvmOrcJit::runtimeMajor);
        expect(NullPointerException.class, () -> LlvmOrcJit.load(null));
        expect(NullPointerException.class, () -> LlvmOrcJit.evaluate(null, 1, 2));
        expect(IllegalArgumentException.class, () -> LlvmOrcJit.load(Path.of("missing-m3-llvm-native.so")));
        Path temp = Files.createTempDirectory("m3-llvm-link-refusal-");
        try {
            Path target = temp.resolve("native.bin");
            Path link = temp.resolve("native-link.bin");
            Files.write(target, new byte[] {1, 2, 3});
            Files.createSymbolicLink(link, target);
            expect(IllegalArgumentException.class, () -> LlvmOrcJit.load(link));
        } finally {
            Files.deleteIfExists(temp.resolve("native-link.bin"));
            Files.deleteIfExists(temp.resolve("native.bin"));
            Files.delete(temp);
        }
        if (LlvmOrcJit.isLoaded()) {
            throw new AssertionError("refused load changed library admission state");
        }
        System.out.println("M3_LLVM_JAVA21_NO_NATIVE_REFUSAL_PASS");
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError("unexpected exception", thrown);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }
}
