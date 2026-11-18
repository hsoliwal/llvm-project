/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * TEST ONLY: deliberately returns LLVM major 19. Never ship this library.
 */
#include <jni.h>

JNIEXPORT jint JNICALL
Java_org_m3jdk_compiler_llvm_LlvmOrcJit_nativeInitialize(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return 19;
}
