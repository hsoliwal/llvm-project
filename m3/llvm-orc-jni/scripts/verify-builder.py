#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
"""Refuse LLVM24 deprecated IRBuilder entrypoint without changing Werror policy.

Original llvm-c/Core.h and llvm/lib/IR/Core.cpp remain source-pinned separately.
This source-level test is NOT a substitute for compiling and running LLVM 24.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[3]
header = (root / "llvm/include/llvm-c/Core.h").read_text(encoding="utf-8")
implementation = (root / "llvm/lib/IR/Core.cpp").read_text(encoding="utf-8")
source = (root / "m3/llvm-orc-jni/src/main/c/m3_llvm_orc_jni.c").read_text(encoding="utf-8")

if 'Use LLVMCreateBuilderForModule instead' not in header:
    raise SystemExit("M3_LLVM24_BUILDER_HEADER_DEPRECATION_CONTRACT_DRIFT")
if "LLVMBuilderRef LLVMCreateBuilderForModule(LLVMModuleRef M);" not in header:
    raise SystemExit("M3_LLVM24_BUILDER_HEADER_MISSING")
if "LLVMBuilderRef LLVMCreateBuilderForModule(LLVMModuleRef M)" not in implementation:
    raise SystemExit("M3_LLVM24_BUILDER_CPP_OWNER_DRIFT")

expected = """#if LLVM_VERSION_MAJOR >= 24
    /* LLVM 24 binds IRBuilder to its owning module and deprecates the
       delayed context-bound builder. Retain the older API for LLVM 19 smoke. */
    builder = LLVMCreateBuilderForModule(module);
#else
    builder = LLVMCreateBuilderInContext(context);
#endif
    LLVMPositionBuilderAtEnd(builder, block);"""

def verify(text: str) -> None:
    if (text.count(expected) != 1
            or text.count("LLVMCreateBuilderForModule(module)") != 1
            or text.count("LLVMCreateBuilderInContext(context)") != 1):
        raise ValueError("M3_LLVM24_BUILDER_VERSION_GUARD_REFUSAL")

verify(source)
for invalid in (
    source.replace("LLVMCreateBuilderForModule(module)", "LLVMCreateBuilderInContext(context)"),
    source.replace("#if LLVM_VERSION_MAJOR >= 24", "#if LLVM_VERSION_MAJOR >= 25"),
    source.replace("LLVMPositionBuilderAtEnd(builder, block);", "LLVMPositionBuilderAtEnd(NULL, block);"),
):
    try:
        verify(invalid)
    except ValueError:
        continue
    raise SystemExit("M3_LLVM24_BUILDER_NEGATIVE_FIXTURE_WAS_ADMITTED")

print("M3_LLVM24_BUILDER_ORIGINAL_OWNER_AND_GUARD_PASS")
