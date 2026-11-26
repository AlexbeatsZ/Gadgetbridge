#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build guarded import-free S4 research probes, including test-only route writes; no install."""
import argparse
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--ndk', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--probe', choices=('stock-ce-rejection', 'get-routing-status', 'get-routing-capture',
                                  'discovery-stop', 'discovery-start', 'test-route-append',
                                  'test-route-restore', 'discovery-slot-check', 'host-test-session',
                                  'host-test-arm', 'host-test-remove', 'host-test-stop', 'native-ipc-test',
                                  'host-spp-session', 'host-spp-pump',
                                  'host-relay-session', 'host-relay-pump',
                                  'relay-route-append', 'relay-route-restore'),
               default='stock-ce-rejection')
a = p.parse_args()
source = Path(__file__).resolve().parent
hosts = list((a.ndk/'toolchains/llvm/prebuilt').glob('*'))
if len(hosts) != 1:
    p.error('expected one host NDK toolchain')
llvm = hosts[0]/'bin'
a.output.mkdir(parents=True, exist_ok=True)
stem = 'stock-ce' if a.probe == 'stock-ce-rejection' else a.probe
obj, elf = a.output/(stem+'.o'), a.output/(stem+'.elf')
probe_source = 'discovery-control' if a.probe in ('discovery-stop', 'discovery-start') else a.probe
defines = ['-DDISCOVERY_START=1'] if a.probe == 'discovery-start' else []
if a.probe=='host-spp-session':
    probe_source='host-test-session';defines=['-DHOST_SPP_BRIDGE=1']
if a.probe.startswith('test-route-'):
    probe_source = 'test-route-write'
    if a.probe == 'test-route-restore':defines = ['-DRESTORE_ORIGINAL=1']
if a.probe.startswith('relay-route-'):
    probe_source = 'relay-route-write'
    if a.probe == 'relay-route-restore':defines = ['-DRESTORE_ORIGINAL=1']
if a.probe in ('host-test-arm','host-test-remove','host-test-stop'):
    probe_source = 'host-test-control'
    defines = ['-DREMOVE_SESSION=1'] if a.probe == 'host-test-remove' else []
    if a.probe == 'host-test-stop':defines = ['-DSTOP_DISCOVERY=1']
subprocess.run([str(llvm/'clang'), '--target=arm-none-eabi', '-mcpu=cortex-m33',
    '-mthumb', '-mno-movt', '-mfloat-abi=soft', '-ffreestanding', '-fno-common',
    '-fno-builtin', '-nostdlib', '-fno-jump-tables', '-fno-stack-protector',
    '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '-Os', '-Wall',
    '-Wextra', '-Werror', *defines, '-c', str(source/(probe_source+'.c')), '-o', str(obj)], check=True)
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
