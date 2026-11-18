#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
# Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
sha256sum --check SOURCE_PINS.sha256
printf 'M3_LLVM_SOURCE_SHA256_PASS\n'
