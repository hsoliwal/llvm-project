/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 */
package org.m3jdk.compiler.llvm;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Experimental, opt-in CPU-only LLVM ORC JIT JNI boundary. This is a
 * compiler-engine smoke test, not a replacement for javac, HotSpot, or Maven.
 *
 * <p>Only the native library supplied explicitly by the caller is loaded. It
 * creates LLVM IR internally for one arithmetic function per call; callers
 * cannot provide arbitrary native code or LLVM IR through this interface.
 * The C layer owns and disposes its LLVM contexts, modules and JIT instances.
 */
public final class LlvmOrcJit {
    /** Exact major of the LLVM fork for which this Java contract is qualified. */
    public static final int LLVM_MAJOR = 24;

    private static volatile Path loadedPath;
    /** A native library already loaded in this JVM cannot be reliably unloaded. */
    private static volatile boolean rejectedNativeLoad;

    private LlvmOrcJit() {}

    /** Exactly the three pure 64-bit, modulo-2^64 arithmetic operations. */
    public enum Operation { ADD, SUBTRACT, MULTIPLY }

    /**
     * Load an explicitly selected native library once. No automatic downloads
     * or dynamic search path probing occur. A different library is refused.
     *
     * @param library absolute or relative path to the trusted native library
     */
    public static synchronized void load(Path library) {
        if (rejectedNativeLoad) {
            throw new IllegalStateException("a previous LLVM JNI load failed; restart the JVM");
        }
        Path path = Objects.requireNonNull(library, "library").toAbsolutePath().normalize();
        if (loadedPath != null) {
            if (!loadedPath.equals(path)) {
                throw new IllegalStateException("a different LLVM JNI library is already loaded");
            }
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("native library is missing or not a regular file: " + path);
        }
        // System.load may register native symbols even when initialization later
        // rejects the library. Do not load a different JNI ABI in this JVM.
        boolean admitted = false;
        try {
            System.load(path.toString());
            int major = nativeInitialize();
            if (major != LLVM_MAJOR) {
                throw new UnsatisfiedLinkError("LLVM JNI major mismatch: expected " + LLVM_MAJOR + ", found " + major);
            }
            loadedPath = path;
            admitted = true;
        } finally {
            if (!admitted) {
                rejectedNativeLoad = true;
            }
        }
    }

    /** Whether the native library has been admitted by {@link #load(Path)}. */
    public static boolean isLoaded() {
        return loadedPath != null;
    }

    /** Return the runtime LLVM major; only valid after the library is loaded. */
    public static int runtimeMajor() {
        requireLoaded();
        return nativeRuntimeMajor();
    }

    /**
     * Execute one internally constructed and LLVM-verified i64 binary function.
     * The results use Java long's wraparound arithmetic, never LLVM's signed
     * overflow/poison flags. This proof path deliberately creates one isolated
     * JIT per call and retains no native handles in Java.
     */
    public static long evaluate(Operation operation, long left, long right) {
        Objects.requireNonNull(operation, "operation");
        requireLoaded();
        return nativeEvaluate(operation.ordinal(), left, right);
    }

    private static void requireLoaded() {
        if (loadedPath == null) {
            throw new IllegalStateException("LLVM JNI is not loaded; call load(Path) explicitly");
        }
    }

    private static native int nativeInitialize();
    private static native int nativeRuntimeMajor();
    private static native long nativeEvaluate(int opcode, long left, long right);
}
