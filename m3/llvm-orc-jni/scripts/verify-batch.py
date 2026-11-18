#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
"""Static original-owner admission for LLVM array-backed ORC JNI.

Actual original LLVM24 headers, code generation, JNI and fault tests remain
separate mandatory runtime gates. This test deliberately refuses tampered
source, version gates, integer-overflow semantics and hidden heap pinning.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "m3/llvm-orc-jni/src/main/c/m3_llvm_orc_batch_jni.c"
JAVA = ROOT / "m3/llvm-orc-jni/src/main/java/org/m3jdk/compiler/llvm/LlvmOrcBatch.java"


def check(c_source: str, java_source: str) -> None:
    if not re.search(r"#define\s+M3_MAX_BATCH_ELEMENTS\s+65536\b", c_source):
        raise ValueError("M3_LLVM_BATCH_C_LIMIT_DRIFT")
    if not re.search(r"public static final int MAX_ELEMENTS\s*=\s*65_536\s*;", java_source):
        raise ValueError("M3_LLVM_BATCH_JAVA_LIMIT_DRIFT")
    if c_source.count("LLVMOrcCreateLLJIT(&jit, NULL)") != 1:
        raise ValueError("M3_LLVM_BATCH_MUST_USE_ONE_JIT")
    if c_source.count("LLVMOrcLLJITLookup(jit, &symbol, \"m3_cpu_batch_op\")") != 1:
        raise ValueError("M3_LLVM_BATCH_LOOKUP_DRIFT")
    if "LLVMVerifyModule(module, LLVMReturnStatusAction, &message)" not in c_source:
        raise ValueError("M3_LLVM_BATCH_IR_NOT_VERIFIED")
    if "#if LLVM_VERSION_MAJOR >= 24\n    builder = LLVMCreateBuilderForModule(module);" not in c_source:
        raise ValueError("M3_LLVM24_BATCH_MODULE_BUILDER_REQUIRED")
    if "#else\n    builder = LLVMCreateBuilderInContext(context);\n#endif" not in c_source:
        raise ValueError("M3_LLVM19_BATCH_FALLBACK_REQUIRED")
    if "LLVMBuildNSW" in c_source or "LLVMBuildNUW" in c_source:
        raise ValueError("M3_LLVM_BATCH_JAVA_OVERFLOW_DRIFT")
    if "GetPrimitiveArrayCritical" in c_source or "GetLongArrayElements" in c_source:
        raise ValueError("M3_LLVM_BATCH_JAVA_HEAP_PINNING_REFUSED")
    if "GetLongArrayRegion" not in c_source or "SetLongArrayRegion" not in c_source:
        raise ValueError("M3_LLVM_BATCH_ARRAY_COPIES_REQUIRED")
    if "if (length < 0 || length > M3_MAX_BATCH_ELEMENTS)" not in c_source:
        raise ValueError("M3_LLVM_BATCH_NATIVE_BUDGET_REFUSED")
    if "if (left.length > MAX_ELEMENTS)" not in java_source:
        raise ValueError("M3_LLVM_BATCH_JAVA_BUDGET_REFUSED")

    for transfer, release, refusal in (
        ("thread_context = LLVMOrcCreateNewThreadSafeContextFromLLVMContext(context);",
         "context = NULL;\n    owns_context = 0;", "if (!thread_context)"),
        ("thread_module = LLVMOrcCreateNewThreadSafeModule(module, thread_context);",
         "module = NULL;", "if (!thread_module)"),
        ("LLVMErrorRef add_error = LLVMOrcLLJITAddLLVMIRModule(",
         "thread_module = NULL;", "if (!m3_batch_error(env, add_error))"),
    ):
        if c_source.count(transfer) != 1:
            raise ValueError("M3_LLVM_BATCH_TRANSFER_CALL_DRIFT")
        i = c_source.index(transfer)
        r = c_source.index(release, i)
        f = c_source.index(refusal, i)
        if not i < r < f:
            raise ValueError("M3_LLVM_BATCH_DOUBLE_DISPOSE_RISK")


if __name__ == "__main__":
    source = SOURCE.read_text(encoding="utf-8")
    java = JAVA.read_text(encoding="utf-8")
    check(source, java)
    poisoned = (
        (source.replace("#define M3_MAX_BATCH_ELEMENTS 65536", "#define M3_MAX_BATCH_ELEMENTS 262144"), java),
        (source.replace("LLVMVerifyModule(module, LLVMReturnStatusAction, &message)",
                        "LLVMVerifyModule(module, LLVMPrintMessageAction, &message)"), java),
        (source.replace("thread_module = LLVMOrcCreateNewThreadSafeModule(module, thread_context);\n    module = NULL;",
                        "thread_module = LLVMOrcCreateNewThreadSafeModule(module, thread_context);"), java),
        (source.replace("LLVMBuildAdd(builder, lhs, rhs, \"sum\")", "LLVMBuildNSWAdd(builder, lhs, rhs, \"sum\")"), java),
        (source.replace("GetLongArrayRegion", "GetLongArrayElements"), java),
        (source, java.replace("65_536", "1048576")),
    )
    for invalid_c, invalid_java in poisoned:
        try:
            check(invalid_c, invalid_java)
        except ValueError:
            continue
        raise SystemExit("M3_LLVM_BATCH_NEGATIVE_SOURCE_FIXTURE_ADMITTED")
    print("M3_LLVM_ORC_BATCH_SOURCE_OWNERSHIP_AND_LIMITS_PASS")
