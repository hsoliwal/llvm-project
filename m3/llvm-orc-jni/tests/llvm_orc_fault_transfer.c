/*
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
 *
 * Linux test-only symbol interposition. Deliberately consumes the LLVM module
 * as the real LLVM C++ owner does, then reports an allocation failure.
 * NEVER package or install as a production JNI dependency.
 */
#include <llvm-c/Core.h>
#include <llvm-c/Orc.h>
#include <stdio.h>

LLVMOrcThreadSafeModuleRef
LLVMOrcCreateNewThreadSafeModule(LLVMModuleRef module, LLVMOrcThreadSafeContextRef thread_context) {
    (void)thread_context;
    LLVMDisposeModule(module);
    fprintf(stderr, "M3_LLVM_TEST_CONSUMED_MODULE_AND_RETURNED_NULL\n");
    return NULL;
}
