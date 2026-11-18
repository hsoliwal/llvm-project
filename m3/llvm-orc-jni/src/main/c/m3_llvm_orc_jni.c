/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 *
 * Original M3 JNI boundary. LLVM remains the code-generation and ORC owner.
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>

#include <llvm/Config/llvm-config.h>
#include <llvm-c/Analysis.h>
#include <llvm-c/Core.h>
#include <llvm-c/Error.h>
#include <llvm-c/LLJIT.h>
#include <llvm-c/Orc.h>
#include <llvm-c/Target.h>

_Static_assert(sizeof(jlong) == sizeof(uint64_t), "JNI jlong must be 64 bits");
_Static_assert(sizeof(uintptr_t) == sizeof(uint64_t), "host ORC must use 64-bit pointers");

typedef uint64_t (*M3BinaryFunction)(uint64_t, uint64_t);
_Static_assert(sizeof(M3BinaryFunction) == sizeof(uintptr_t), "ORC host function pointer mismatch");

static void m3_throw(JNIEnv *env, const char *class_name, const char *message) {
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls != NULL) {
        (void)(*env)->ThrowNew(env, cls, message);
    }
}

static int m3_check_error(JNIEnv *env, LLVMErrorRef error) {
    if (!error) return 1;
    char *message = LLVMGetErrorMessage(error); /* consumes the LLVMErrorRef */
    m3_throw(env, "java/lang/IllegalStateException", message ? message : "LLVM ORC failure");
    if (message) LLVMDisposeErrorMessage(message);
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_m3jdk_compiler_llvm_LlvmOrcJit_nativeInitialize(JNIEnv *env, jclass clazz) {
    (void)clazz;
    unsigned major = 0, minor = 0, patch = 0;
    LLVMGetVersion(&major, &minor, &patch);
    if (major != LLVM_VERSION_MAJOR) {
        m3_throw(env, "java/lang/UnsatisfiedLinkError", "linked LLVM does not match compiled LLVM headers");
        return -1;
    }
    if (LLVMInitializeNativeTarget() || LLVMInitializeNativeAsmPrinter()) {
        m3_throw(env, "java/lang/UnsatisfiedLinkError", "host LLVM target or assembly printer unavailable");
        return -1;
    }
    return (jint)major;
}

JNIEXPORT jint JNICALL
Java_org_m3jdk_compiler_llvm_LlvmOrcJit_nativeRuntimeMajor(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    unsigned major = 0;
    LLVMGetVersion(&major, NULL, NULL);
    return (jint)major;
}

JNIEXPORT jlong JNICALL
Java_org_m3jdk_compiler_llvm_LlvmOrcJit_nativeEvaluate(
        JNIEnv *env, jclass clazz, jint opcode, jlong left, jlong right) {
    (void)clazz;
    jlong result = 0;
    LLVMOrcLLJITRef jit = NULL;
    LLVMContextRef context = NULL;
    int owns_context = 1;
    LLVMModuleRef module = NULL;
    LLVMBuilderRef builder = NULL;
    LLVMOrcThreadSafeContextRef thread_context = NULL;
    LLVMOrcThreadSafeModuleRef thread_module = NULL;
    LLVMOrcExecutorAddress symbol = 0;

    if (opcode < 0 || opcode > 2) {
        m3_throw(env, "java/lang/IllegalArgumentException", "unknown LLVM binary operation");
        return 0;
    }
    if (!m3_check_error(env, LLVMOrcCreateLLJIT(&jit, NULL))) goto cleanup;
#if LLVM_VERSION_MAJOR >= 24
    context = LLVMContextCreate();
#else
    /* LLVM 19 host smoke: the old C API provides a borrowed context getter.
       LLVM 24 uses the new ownership-taking constructor below instead. */
    thread_context = LLVMOrcCreateNewThreadSafeContext();
    if (thread_context) context = LLVMOrcThreadSafeContextGetContext(thread_context);
    owns_context = 0;
#endif
    if (!context) {
        m3_throw(env, "java/lang/OutOfMemoryError", "LLVM context allocation failed");
        goto cleanup;
    }
    module = LLVMModuleCreateWithNameInContext("m3_cpu_binary", context);
    if (!module) {
        m3_throw(env, "java/lang/OutOfMemoryError", "LLVM module allocation failed");
        goto cleanup;
    }
    LLVMSetTarget(module, LLVMOrcLLJITGetTripleString(jit));
    LLVMSetDataLayout(module, LLVMOrcLLJITGetDataLayoutStr(jit));

    LLVMTypeRef i64 = LLVMInt64TypeInContext(context);
    LLVMTypeRef args[2] = {i64, i64};
    LLVMTypeRef signature = LLVMFunctionType(i64, args, 2, 0);
    LLVMValueRef function = LLVMAddFunction(module, "m3_cpu_op", signature);
    LLVMBasicBlockRef block = LLVMAppendBasicBlockInContext(context, function, "entry");
    builder = LLVMCreateBuilderInContext(context);
    LLVMPositionBuilderAtEnd(builder, block);
    LLVMValueRef a = LLVMGetParam(function, 0);
    LLVMValueRef b = LLVMGetParam(function, 1);
    LLVMValueRef value = NULL;
    switch (opcode) {
        case 0: value = LLVMBuildAdd(builder, a, b, "sum"); break;
        case 1: value = LLVMBuildSub(builder, a, b, "difference"); break;
        case 2: value = LLVMBuildMul(builder, a, b, "product"); break;
        default: break; /* validated above */
    }
    if (!value) {
        m3_throw(env, "java/lang/IllegalStateException", "LLVM instruction creation failed");
        goto cleanup;
    }
    (void)LLVMBuildRet(builder, value);
    LLVMDisposeBuilder(builder);
    builder = NULL;

    char *diagnostic = NULL;
    LLVMBool invalid = LLVMVerifyModule(module, LLVMReturnStatusAction, &diagnostic);
    if (invalid) m3_throw(env, "java/lang/IllegalStateException",
                         diagnostic ? diagnostic : "LLVM IR verification failed");
    if (diagnostic) LLVMDisposeMessage(diagnostic);
    if (invalid) goto cleanup;

#if LLVM_VERSION_MAJOR >= 24
    /* LLVM 24 transfers ownership of the existing LLVM context. */
    /* LLVM's C++ owner constructs std::unique_ptr<LLVMContext> immediately.
       Do not dispose this context again, even if ORC returns an error. */
    thread_context = LLVMOrcCreateNewThreadSafeContextFromLLVMContext(context);
    context = NULL;
    owns_context = 0;
    if (!thread_context) {
        m3_throw(env, "java/lang/OutOfMemoryError", "ORC thread-safe context allocation failed");
        goto cleanup;
    }
#endif
    /* LLVM's C++ owner immediately wraps M in a unique_ptr<Module>.
       Clear our handle at transfer, not only when construction succeeds. */
    thread_module = LLVMOrcCreateNewThreadSafeModule(module, thread_context);
    module = NULL;
    if (!thread_module) {
        m3_throw(env, "java/lang/OutOfMemoryError", "ORC module allocation failed");
        goto cleanup;
    }
    LLVMOrcDisposeThreadSafeContext(thread_context); /* TSM retains its shared context. */
    thread_context = NULL;

    /* LLVMOrcLLJITAddLLVMIRModule always consumes its TSM argument. */
    LLVMErrorRef add_error = LLVMOrcLLJITAddLLVMIRModule(
            jit, LLVMOrcLLJITGetMainJITDylib(jit), thread_module);
    thread_module = NULL;
    if (!m3_check_error(env, add_error)) goto cleanup;
    if (!m3_check_error(env, LLVMOrcLLJITLookup(jit, &symbol, "m3_cpu_op"))) goto cleanup;
    if (!symbol) {
        m3_throw(env, "java/lang/IllegalStateException", "missing generated ORC symbol");
        goto cleanup;
    }

    /* The ORC executor address is an in-process host function pointer only. */
    uintptr_t pointer_bits = (uintptr_t)symbol;
    M3BinaryFunction generated = NULL;
    memcpy(&generated, &pointer_bits, sizeof(generated));
    uint64_t lhs = 0, rhs = 0;
    memcpy(&lhs, &left, sizeof(lhs));
    memcpy(&rhs, &right, sizeof(rhs));
    uint64_t answer = generated(lhs, rhs);
    memcpy(&result, &answer, sizeof(result));

cleanup:
    if (builder) LLVMDisposeBuilder(builder);
    if (thread_module) LLVMOrcDisposeThreadSafeModule(thread_module);
    if (module) LLVMDisposeModule(module);
    if (thread_context) LLVMOrcDisposeThreadSafeContext(thread_context);
    if (owns_context && context) LLVMContextDispose(context);
    if (jit) {
        LLVMErrorRef dispose_error = LLVMOrcDisposeLLJIT(jit);
        if ((*env)->ExceptionCheck(env)) {
            if (dispose_error) LLVMConsumeError(dispose_error);
        } else {
            (void)m3_check_error(env, dispose_error);
        }
    }
    return result;
}
