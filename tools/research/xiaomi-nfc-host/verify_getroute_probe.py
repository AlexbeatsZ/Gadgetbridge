#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE execute the compiled read-only probe and original S4 queue/response.

Links a separate fixed-address test image from the module object. Never installs
either image. GKI task enqueue, HAL output, allocation and timers are emulated.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess

from verify_rf_data import BASE, CE, DM, NFC, STOP
from verify_nci_routing import NciMachine, RAW_RESPONSE, RESPONSE_MESSAGE

TEXT, DATA = 0x3D008000, 0x3D009000


class ProbeMachine(NciMachine):
    def __init__(self, image, probe):
        super().__init__(image)
        self.enqueued = []
        self.cpu.mem_write(TEXT, probe)
        self.cpu.mem_write(CE + 0xAC, struct.pack('<I', 0x8101))
        self.ce_before = bytes(self.cpu.mem_read(CE, 0xD4))
        self.cpu.mem_write(DM + 0x4C, b'\x01')

    def _trace(self, cpu, address, size, data):
        if address == BASE + 0x18DE2C:
            self.enqueued.append(cpu.reg_read(self.regs[0]))
            self._return()
        else:
            super()._trace(cpu, address, size, data)

    def result(self):
        return list(struct.unpack('<16I', bytes(self.cpu.mem_read(DATA, 64))))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    p.add_argument('--llvm', type=Path, required=True)
    p.add_argument('--build-dir', type=Path, required=True)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    test_elf = a.build_dir/'get-routing-status-offline.elf'
    test_bin = a.build_dir/'get-routing-status-offline.bin'
    script = a.build_dir/'get-routing-status-offline.ld'
    script.write_text('SECTIONS { . = '+hex(TEXT)+'; .text : { *(.text .text.*) }\n'
                      '. = '+hex(DATA)+'; .data : { *(.data.result) *(.data .data.*) }\n'
                      '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
    subprocess.run([str(a.llvm/'ld.lld'), '-T', str(script),
                    '--entry=module_initialize', str(a.build_dir/'get-routing-status.o'),
                    '-o', str(test_elf)], check=True)
    subprocess.run([str(a.llvm/'llvm-objcopy'), '-O', 'binary', '--only-section=.text',
                    '--only-section=.data', str(test_elf), str(test_bin)], check=True)
    image, probe = a.firmware.read_bytes(), test_bin.read_bytes()
    # The standalone linker may leave .data.result as an orphan before .data;
    # require the exact result magic at the intended address rather than guess.
    if probe[DATA-TEXT:DATA-TEXT+4] != struct.pack('<I', 0x5254534E):
        raise SystemExit('offline result section address differs; do not run probe')
    cases = []
    m = ProbeMachine(image, probe)
    m.call(TEXT-BASE)
    assert m.result()[1] == 2 and len(m.enqueued) == 1
    message = m.enqueued[0]
    assert bytes(m.cpu.mem_read(message+20, 3)) == bytes.fromhex('210200')
    assert m.call(0x181334, message) == 0 and m.commands == ['210200']
    assert m.freed == [message]
    response = bytes.fromhex('41020100')
    m.cpu.mem_write(RESPONSE_MESSAGE, struct.pack('<4H', 0x4000, 4, 1, 0))
    m.cpu.mem_write(RESPONSE_MESSAGE+9, response)
    m.call(RAW_RESPONSE, RESPONSE_MESSAGE)
    assert m.result()[1] == 3 and m.result()[8:13] == [1, 0x42, 4, 0, 0x00010241]
    assert m.read32(NFC+0x140) == 0 and bytes(m.cpu.mem_read(NFC+0xBC, 1)) == b'\0'
    cases.append({'case': 'compiled_probe_original_queue_response_and_callback_cleanup', 'passed': True})
    for name, address, data in (
        ('busy_callback', NFC+0x140, struct.pack('<I', STOP|1)),
        ('raw_command_busy', NFC+0xBC, b'\x01'),
        ('command_window_closed', NFC+0x1CF, b'\0'),
        ('queued_other_command', NFC+0x14C, b'\x01\x00'),
        ('active_rf_state', DM+0x4C, b'\x05'),
        ('unexpected_ese_state', CE+0xAC, struct.pack('<I', 0x8100)),
    ):
        m = ProbeMachine(image, probe)
        m.cpu.mem_write(address, data)
        m.ce_before = bytes(m.cpu.mem_read(CE, 0xD4))
        m.call(TEXT-BASE)
        assert m.result()[1] == 11 and not m.enqueued and not m.commands
        cases.append({'case': name+'_refuses_query', 'passed': True})
    result = {'execution': 'OFFLINE_COMPILED_PROBE_AND_ORIGINAL_ARM_WITH_SYNTHETIC_RAM',
              'real_controller_query': False, 'cases_passed': len(cases), 'cases': cases,
              'not_verified': ['live callback completion', 'real command timeout',
                               'routing notification capture', 'live controller support']}
    if a.output:
        a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
