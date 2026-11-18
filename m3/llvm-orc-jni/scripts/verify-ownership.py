#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
"""Check exact C++ LLVM ORC ownership contracts and JNI transfer order.

This is static source qualification; native execution is independently required.
"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[3]
source = (root / "m3/llvm-orc-jni/src/main/c/m3_llvm_orc_jni.c").read_text(encoding="utf-8")
upstream = (root / "llvm/lib/ExecutionEngine/Orc/OrcV2CBindings.cpp").read_text(encoding="utf-8")

owners = [
    ("LLVMOrcCreateNewThreadSafeContextFromLLVMContext", "std::unique_ptr<LLVMContext>(unwrap(Ctx))"),
    ("LLVMOrcCreateNewThreadSafeModule", "std::unique_ptr<Module>(unwrap(M))"),
]
for function, owning_expression in owners:
    start = upstream.index(function + "(")
    # The new context owner appears in a delegating function before its definition.
    region = upstream[start:start + 1000]
    if owning_expression not in region:
        raise SystemExit("M3_LLVM_ORIGINAL_OWNER_SOURCE_DRIFT: " + function)

for call, released, refused in [
    ("thread_context = LLVMOrcCreateNewThreadSafeContextFromLLVMContext(context);",
     "context = NULL;\n    owns_context = 0;", "if (!thread_context)"),
    ("thread_module = LLVMOrcCreateNewThreadSafeModule(module, thread_context);",
     "module = NULL;", "if (!thread_module)"),
]:
    i = source.index(call)
    release = source.index(released, i)
    failure = source.index(refused, i)
    if not i < release < failure:
        raise SystemExit("M3_LLVM_JNI_DOUBLE_DISPOSE_SOURCE_REFUSAL: " + call)

print("M3_LLVM_ORIGINAL_ORC_OWNERSHIP_SOURCE_ORDER_PASS")
