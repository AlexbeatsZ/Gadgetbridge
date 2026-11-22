#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build the guarded, import-free stock CE rejection diagnostic; no install."""
import argparse
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndk', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
source = Path(__file__).resolve().parent
hosts = list((a.ndk/'toolchains/llvm/prebuilt').glob('*'))
if len(hosts) != 1:
    p.error('expected one host NDK toolchain')
llvm = hosts[0]/'bin'
a.output.mkdir(parents=True, exist_ok=True)
obj, elf = a.output/'stock-ce.o', a.output/'stock-ce.elf'
subprocess.run([str(llvm/'clang'), '--target=arm-none-eabi', '-mcpu=cortex-m33',
    '-mthumb', '-mno-movt', '-mfloat-abi=soft', '-ffreestanding', '-fno-common',
    '-fno-builtin', '-nostdlib', '-fno-jump-tables', '-fno-stack-protector',
    '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '-Os', '-Wall',
    '-Wextra', '-Werror', '-c', str(source/'stock-ce-rejection.c'), '-o', str(obj)], check=True)
subprocess.run([str(llvm/'ld.lld'), '-r', '-T', str(source/'stock-ce-rejection.ld'),
                str(obj), '-o', str(elf)], check=True)
subprocess.run([str(llvm/'llvm-objcopy'), '--remove-section=.ARM.exidx',
    '--remove-section=.rel.ARM.exidx', '--remove-section=.fini_array',
    '--remove-section=.rel.fini_array', '--remove-section=.llvm_addrsig', str(elf)], check=True)
symbols = subprocess.check_output([str(llvm/'llvm-nm'), '--undefined-only', str(elf)], text=True)
if symbols.strip():
    raise SystemExit('refusing module with undefined imports: '+symbols)
subprocess.run([str(llvm/'llvm-readelf'), '-h', '-r', '-s', str(elf)], check=True)
print(elf)
