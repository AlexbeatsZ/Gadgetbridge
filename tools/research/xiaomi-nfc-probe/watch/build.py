#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build an import-free NuttX module and embed it in the read-only Lua diagnostic."""
import argparse
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndk', required=True, type=Path)
p.add_argument('--output', required=True, type=Path, help='task temporary directory')
a = p.parse_args()
base = Path(__file__).resolve().parent
hosts = list((a.ndk / 'toolchains/llvm/prebuilt').glob('*'))
if len(hosts) != 1:
    p.error('expected exactly one host NDK toolchain')
bin_dir = hosts[0] / 'bin'
a.output.mkdir(parents=True, exist_ok=True)
obj = a.output / 'module-probe.o'
elf = a.output / 'module-probe.elf'
subprocess.run([str(bin_dir/'clang'), '--target=arm-none-eabi', '-mcpu=cortex-m33',
                '-mthumb', '-mfloat-abi=soft', '-ffreestanding', '-fno-common',
                '-fno-builtin', '-nostdlib', '-fno-jump-tables', '-fno-stack-protector',
                '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '-Os',
                '-Wall', '-Wextra', '-Werror', '-c', str(base/'module-probe.c'), '-o', str(obj)], check=True)
subprocess.run([str(bin_dir/'ld.lld'), '-r', '-T', str(base/'module-probe.ld'), str(obj), '-o', str(elf)], check=True)
subprocess.run([str(bin_dir/'llvm-objcopy'), '--remove-section=.ARM.exidx',
                '--remove-section=.rel.ARM.exidx', '--remove-section=.fini_array',
                '--remove-section=.rel.fini_array', '--remove-section=.llvm_addrsig', str(elf)], check=True)
subprocess.run([str(bin_dir/'llvm-readelf'), '-h', '-r', '-s', str(elf)], check=True)
lua = (base/'read-only.lua.in').read_text().replace('@MODULE_HEX@', elf.read_bytes().hex())
(a.output/'main.lua').write_text(lua)
print(a.output/'main.lua')
