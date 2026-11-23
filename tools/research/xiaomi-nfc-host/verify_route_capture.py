#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE compiled routing-only tap + original HAL trampoline and NCI queue.

Forwarding into the original HAL receiver is an explicit emulator boundary.
Sequential callback delivery does not establish the live task-owner ABI. Actual
unload additionally requires an original-HAL query after the tap self-detaches.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess

from verify_rf_data import BASE, CE, DM, NFC, STOP
from verify_nci_routing import NciMachine

TEXT, DATA, INPUT = 0x3D008000, 0x3D009000, 0x3D002000
HAL_SLOT, ORIGINAL_HAL, TRAMPOLINE = 0x3C2D45CC, 0x2C34CD85, 0x180D34


class CaptureMachine(NciMachine):
    def __init__(self, image, probe):
        super().__init__(image)
        self.enqueued, self.forwarded = [], []
        self.cpu.mem_write(TEXT, probe)
        self.write32(HAL_SLOT, ORIGINAL_HAL)
        self.write32(0x3C2D3C20, BASE+TRAMPOLINE+1)
        self.write32(NFC+0x1D4, 0x3C2D3738)
        self.write32(0x3C2D3738+0x18, 0x3D000401)
        self.cpu.mem_write(CE+0xAC, struct.pack('<I', 0x8101))
        self.ce_before = bytes(self.cpu.mem_read(CE, 0xD4))
        self.cpu.mem_write(DM+0x4C, b'\x01')

    def _trace(self, cpu, address, size, data):
        if address == BASE+0x18DE2C:
            self.enqueued.append(cpu.reg_read(self.regs[0]))
            self._return()
        elif address == ORIGINAL_HAL & ~1:
            length, pointer = (cpu.reg_read(r) for r in self.regs[:2])
            self.forwarded.append(bytes(cpu.mem_read(pointer, length)))
            self._return()
        else:
            super()._trace(cpu, address, size, data)

    def header(self):
        return list(struct.unpack('<32I', bytes(self.cpu.mem_read(DATA, 128))))

    def frames(self):
        lengths = struct.unpack('<4I', bytes(self.cpu.mem_read(DATA+128, 16)))
        offset, frames = 0, []
        for length in lengths:
            if length:
                frames.append(bytes(self.cpu.mem_read(DATA+144+offset, length)))
                offset += length
        return frames

    def arm(self):
        self.call(TEXT-BASE)
        assert self.header()[1] == 2 and len(self.enqueued) == 1
        assert self.call(0x181334, self.enqueued[0]) == 0
        assert self.commands == ['210200'] and self.freed == self.enqueued
        assert self.read32(NFC+0x140) == 0  # First query has no module response callback.

    def deliver(self, packet):
        self.cpu.mem_write(INPUT, packet or b'\0')
        self.call(TRAMPOLINE, len(packet), INPUT)
        assert self.header()[7] == 0
        assert self.forwarded[-1] == packet


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    p.add_argument('--llvm', type=Path, required=True)
    p.add_argument('--build-dir', type=Path, required=True)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    ld = a.build_dir/'get-routing-capture-offline.ld'
    elf, binary = (a.build_dir/('get-routing-capture-offline'+suffix) for suffix in ('.elf', '.bin'))
    ld.write_text('SECTIONS { . = '+hex(TEXT)+'; .text : { *(.text .text.*) }\n'
                  '. = '+hex(DATA)+'; .data : { *(.data.result) *(.data .data.*) }\n'
                  '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
    subprocess.run([str(a.llvm/'ld.lld'), '-T', str(ld), '--entry=module_initialize',
                    str(a.build_dir/'get-routing-capture.o'), '-o', str(elf)], check=True)
    subprocess.run([str(a.llvm/'llvm-objcopy'), '-O', 'binary', '--only-section=.text',
                    '--only-section=.data', str(elf), str(binary)], check=True)
    image, probe = a.firmware.read_bytes(), binary.read_bytes()
    assert probe[DATA-TEXT:DATA-TEXT+4] == struct.pack('<I', 0x52544350)
    cases = []
    def passed(name):
        cases.append({'case': name, 'passed': True})
    m = CaptureMachine(image, probe); m.arm()
    m.deliver(bytes.fromhex('41020100'))
    packet = bytes.fromhex('61020700010103c00104')
    m.deliver(packet)
    assert m.header()[1] == 3 and m.header()[8:11] == [1, 0, len(packet)]
    assert m.frames() == [packet] and m.read32(HAL_SLOT) == ORIGINAL_HAL
    before = m.header()
    m.deliver(bytes.fromhex('41020100'))
    assert m.header() == before  # A later original-HAL query never enters tap.
    passed('complete_notification_self_detach_then_original_hal_barrier')

    m = CaptureMachine(image, probe); m.arm(); m.deliver(bytes.fromhex('41020100'))
    first, last = bytes.fromhex('61020701010103c00104'), bytes.fromhex('6102020000')
    m.deliver(first)
    assert m.header()[1] == 2 and not m.header()[8]
    m.deliver(last)
    assert m.frames() == [first, last] and m.header()[8] == 1
    passed('more_notification_segments_preserved_in_order')

    m = CaptureMachine(image, probe); m.arm()
    for packet in (bytes.fromhex('000003010203'), bytes.fromhex('61050100'), b''):
        m.deliver(packet)
    assert not m.frames() and m.header()[14] == 3
    assert not any(bytes(m.cpu.mem_read(DATA+144, 1040)))
    passed('all_other_packets_forwarded_without_payload_logging')

    m = CaptureMachine(image, probe); m.arm(); m.deliver(bytes.fromhex('41020100'))
    m.deliver(bytes.fromhex('41020100'))
    assert m.header()[1] == 4 and not m.header()[8] and m.read32(HAL_SLOT) == ORIGINAL_HAL
    passed('no_notification_second_read_only_query_forces_self_detach')

    m = CaptureMachine(image, probe); m.arm(); m.deliver(bytes.fromhex('41020103'))
    assert m.header()[1] == 4 and m.header()[12] == 3 and not m.header()[8]
    passed('rejected_controller_query_self_detaches')

    for name, packet in (
        ('short_notification', bytes.fromhex('610200')),
        ('wrong_payload_length', bytes.fromhex('6102070000')),
        ('invalid_more', bytes.fromhex('6102020200')),
    ):
        m = CaptureMachine(image, probe); m.arm(); m.deliver(bytes.fromhex('41020100'))
        m.deliver(packet)
        assert m.header()[9] == 3 and not m.header()[8] and m.read32(HAL_SLOT) == ORIGINAL_HAL
        assert m.frames() == [packet]
        passed(name+'_retained_as_bad_evidence_not_backup')
    m = CaptureMachine(image, probe); m.arm(); m.deliver(bytes.fromhex('6102020000'))
    assert m.header()[9] == 2 and not m.header()[8]
    passed('notification_before_response_not_accepted_as_backup')

    m = CaptureMachine(image, probe); m.arm(); m.deliver(bytes.fromhex('41020100'))
    frame = bytes.fromhex('6102ff0100') + bytes(253)
    for _ in range(4):m.deliver(frame)
    m.deliver(bytes.fromhex('6102020100'))
    assert m.header()[9] == 4 and m.header()[10] == 1032 and not m.header()[8]
    assert len(m.frames()) == 4 and m.read32(HAL_SLOT) == ORIGINAL_HAL
    passed('notification_bound_overflow_detaches_without_truncated_backup')

    for name, address, value in (
        ('unexpected_hal_owner', HAL_SLOT, 0x3D000101),
        ('unexpected_hal_trampoline', 0x3C2D3C20, 0x3D000101),
        ('unexpected_hal_table', NFC+0x1D4, 0x3D003000),
        ('busy_nci_callback', NFC+0x140, 0x3D000101),
    ):
        m = CaptureMachine(image, probe); m.write32(address, value)
        m.call(TEXT-BASE)
        assert m.header()[1] == 11 and not m.enqueued
        assert m.read32(HAL_SLOT) == (value if address == HAL_SLOT else ORIGINAL_HAL)
        passed(name+'_refuses_install')
    result = {'execution': 'OFFLINE_COMPILED_TAP_AND_ORIGINAL_ARM_WITH_SYNTHETIC_RAM',
              'real_notification_capture': False, 'cases_passed': len(cases), 'cases': cases,
              'boundaries': ['original HAL receiver', 'GKI allocation/free/enqueue',
                             'HAL output', 'timers'],
              'not_verified': ['live HAL callback owner and serialization',
                               'live original-HAL quiescence barrier',
                               'real routing table', 'host AID routing', 'external RF']}
    if a.output:a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':main()
