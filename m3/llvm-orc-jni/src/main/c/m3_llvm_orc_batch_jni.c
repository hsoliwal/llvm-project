/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 *
 * LLVM original ORC owns code generation. Bounded array-backed M3 JNI lane:
 * compile one verified i64 kernel per batch; never export executable handles.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <llvm/Config/llvm-config.h>
#include <llvm-c/Analysis.h>
#include <llvm-c/Core.h>
#include <llvm-c/Error.h>
#include <llvm-c/LLJIT.h>
#include <llvm-c/Orc.h>
#include <llvm-c/Target.h>

#define M3_MAX_BATCH_ELEMENTS 65536
_Static_assert(sizeof(jlong) == sizeof(uint64_t), "JNI long must have 64 bits");
_Static_assert(sizeof(uintptr_t) == sizeof(uint64_t), "M3 ORC requires 64-bit host pointers");

typedef uint64_t (*M3BatchKernel)(uint64_t, uint64_t);
_Static_assert(sizeof(M3BatchKernel) == sizeof(uintptr_t), "ORC pointer size mismatch");

static void m3_batch_throw(JNIEnv *env, const char *class_name, const char *message) {
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls != NULL) (void)(*env)->ThrowNew(env, cls, message);
}

static int m3_batch_error(JNIEnv *env, LLVMErrorRef error) {
    if (!error) return 1;
    char *text = LLVMGetErrorMessage(error); /* consumes error */
    m3_batch_throw(env, "java/lang/IllegalStateException", text ? text : "LLVM ORC failure");
    if (text) LLVMDisposeErrorMessage(text);
    return 0;
}

JNIEXPORT jlongArray JNICALL
Java_org_m3jdk_compiler_llvm_LlvmOrcBatch_nativeEvaluateBatch(
        JNIEnv *env, jclass clazz, jint opcode, jlongArray left, jlongArray right) {
    (void)clazz;
    jlongArray output = NULL;
    jlong *scratch = NULL;
    LLVMOrcLLJITRef jit = NULL;
    LLVMContextRef context = NULL;
    int owns_context = 1;
    LLVMModuleRef module = NULL;
    LLVMBuilderRef builder = NULL;
    LLVMOrcThreadSafeContextRef thread_context = NULL;
    LLVMOrcThreadSafeModuleRef thread_module = NULL;
    LLVMOrcExecutorAddress symbol = 0;

    if (!left || !right) {
        m3_batch_throw(env, "java/lang/NullPointerException", "LLVM batch array is null");
        goto cleanup;
    }
    if (opcode < 0 || opcode > 2) {
        m3_batch_throw(env, "java/lang/IllegalArgumentException", "invalid LLVM batch opcode");
        goto cleanup;
    }
    jsize length = (*env)->GetArrayLength(env, left);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    if (length != (*env)->GetArrayLength(env, right)) {
        m3_batch_throw(env, "java/lang/IllegalArgumentException", "LLVM batch lengths differ");
        goto cleanup;
    }
    if (length < 0 || length > M3_MAX_BATCH_ELEMENTS) {
        m3_batch_throw(env, "java/lang/IllegalArgumentException", "LLVM batch limit exceeded");
        goto cleanup;
    }
    if (length == 0) {
        output = (*env)->NewLongArray(env, 0);
        goto cleanup;
    }

    /* Three contiguous primitive lanes, no per-entry objects or pinned Java heap. */
    size_t bytes = (size_t)length * sizeof(jlong);
    scratch = malloc(bytes * 3U);
    if (!scratch) {
        m3_batch_throw(env, "java/lang/OutOfMemoryError", "LLVM batch lanes allocation failed");
        goto cleanup;
    }
    jlong *a = scratch, *b = scratch + length, *result = scratch + 2 * length;
    (*env)->GetLongArrayRegion(env, left, 0, length, a);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    (*env)->GetLongArrayRegion(env, right, 0, length, b);
    if ((*env)->ExceptionCheck(env)) goto cleanup;

    if (!m3_batch_error(env, LLVMOrcCreateLLJIT(&jit, NULL))) goto cleanup;
#if LLVM_VERSION_MAJOR >= 24
    context = LLVMContextCreate();
#else
    thread_context = LLVMOrcCreateNewThreadSafeContext();
    if (thread_context) context = LLVMOrcThreadSafeContextGetContext(thread_context);
    owns_context = 0;
#endif
    if (!context) {
        m3_batch_throw(env, "java/lang/OutOfMemoryError", "LLVM batch context allocation failed");
        goto cleanup;
    }
    module = LLVMModuleCreateWithNameInContext("m3_cpu_batch", context);
    if (!module) {
        m3_batch_throw(env, "java/lang/OutOfMemoryError", "LLVM batch module allocation failed");
        goto cleanup;
    }
    LLVMSetTarget(module, LLVMOrcLLJITGetTripleString(jit));
    LLVMSetDataLayout(module, LLVMOrcLLJITGetDataLayoutStr(jit));
    LLVMTypeRef i64 = LLVMInt64TypeInContext(context);
    LLVMTypeRef args[2] = {i64, i64};
    LLVMTypeRef signature = LLVMFunctionType(i64, args, 2, 0);
    LLVMValueRef function = LLVMAddFunction(module, "m3_cpu_batch_op", signature);
    LLVMBasicBlockRef block = LLVMAppendBasicBlockInContext(context, function, "entry");
#if LLVM_VERSION_MAJOR >= 24
    builder = LLVMCreateBuilderForModule(module);
#else
    builder = LLVMCreateBuilderInContext(context);
#endif
    if (!builder) {
        m3_batch_throw(env, "java/lang/OutOfMemoryError", "LLVM batch builder allocation failed");
        goto cleanup;
    }
    LLVMPositionBuilderAtEnd(builder, block);
    LLVMValueRef lhs = LLVMGetParam(function, 0), rhs = LLVMGetParam(function, 1);
    LLVMValueRef value = NULL;
    switch (opcode) {
        case 0: value = LLVMBuildAdd(builder, lhs, rhs, "sum"); break;
        case 1: value = LLVMBuildSub(builder, lhs, rhs, "difference"); break;
        case 2: value = LLVMBuildMul(builder, lhs, rhs, "product"); break;
        default: break; /* Refused above. */
    }
    if (!value) {
        m3_batch_throw(env, "java/lang/IllegalStateException", "LLVM batch instruction unavailable");
        goto cleanup;
    }
    (void)LLVMBuildRet(builder, value);
    LLVMDisposeBuilder(builder);
    builder = NULL;

    char *message = NULL;
    LLVMBool invalid = LLVMVerifyModule(module, LLVMReturnStatusAction, &message);
    if (invalid) {
        m3_batch_throw(env, "java/lang/IllegalStateException",
                message ? message : "LLVM batch IR verification failed");
    }
    if (message) LLVMDisposeMessage(message);
    if (invalid) goto cleanup;

#if LLVM_VERSION_MAJOR >= 24
    /* LLVM's C++ std::unique_ptr takes the original context immediately. */
    thread_context = LLVMOrcCreateNewThreadSafeContextFromLLVMContext(context);
    context = NULL;
    owns_context = 0;
    if (!thread_context) {
        m3_batch_throw(env, "java/lang/OutOfMemoryError", "LLVM batch thread context failed");
        goto cleanup;
    }
#endif
    thread_module = LLVMOrcCreateNewThreadSafeModule(module, thread_context);
    module = NULL; /* Original LLVM consumes the module, including on failure. */
    if (!thread_module) {
        m3_batch_throw(env, "java/lang/OutOfMemoryError", "LLVM batch thread module failed");
        goto cleanup;
    }
    LLVMOrcDisposeThreadSafeContext(thread_context);
    thread_context = NULL;

    LLVMErrorRef add_error = LLVMOrcLLJITAddLLVMIRModule(
            jit, LLVMOrcLLJITGetMainJITDylib(jit), thread_module);
    thread_module = NULL; /* Always consumed by the original LLVM C API. */
    if (!m3_batch_error(env, add_error)) goto cleanup;
    if (!m3_batch_error(env, LLVMOrcLLJITLookup(jit, &symbol, "m3_cpu_batch_op"))) goto cleanup;
    if (!symbol) {
        m3_batch_throw(env, "java/lang/IllegalStateException", "LLVM batch JIT symbol missing");
        goto cleanup;
    }

    uintptr_t address_bits = (uintptr_t)symbol;
    M3BatchKernel kernel = NULL;
    memcpy(&kernel, &address_bits, sizeof(kernel));
    for (jsize i = 0; i < length; ++i) {
        uint64_t x, y, answer;
        memcpy(&x, &a[i], sizeof(x));
        memcpy(&y, &b[i], sizeof(y));
        answer = kernel(x, y);
        memcpy(&result[i], &answer, sizeof(answer));
    }
    output = (*env)->NewLongArray(env, length);
    if (!output || (*env)->ExceptionCheck(env)) goto cleanup;
    (*env)->SetLongArrayRegion(env, output, 0, length, result);
    if ((*env)->ExceptionCheck(env)) output = NULL;

cleanup:
    if (builder) LLVMDisposeBuilder(builder);
    if (thread_module) LLVMOrcDisposeThreadSafeModule(thread_module);
    if (module) LLVMDisposeModule(module);
    if (thread_context) LLVMOrcDisposeThreadSafeContext(thread_context);
    if (owns_context && context) LLVMContextDispose(context);
    if (jit) {
        LLVMErrorRef disposal = LLVMOrcDisposeLLJIT(jit);
        if ((*env)->ExceptionCheck(env)) {
            if (disposal) LLVMConsumeError(disposal);
        } else {
            (void)m3_batch_error(env, disposal);
        }
    }
    free(scratch);
    return (*env)->ExceptionCheck(env) ? NULL : output;
}
