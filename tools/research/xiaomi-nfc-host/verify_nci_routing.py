#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE original S4 NCI routing/raw-command ABI; never contacts hardware.

GET_ROUTING response status and routing-table notifications are distinct. This
does not implement notification capture, a live route backup, or route writes.
All allocations, HAL output, timers and application callbacks are emulated.
"""
import argparse
import json
from pathlib import Path
import struct

from verify_rf_data import BASE, DM, NFC, MESSAGE, EVENT_DATA, OfflineMachine, guard

SET_ROUTING = 0x18E290
RAW_ACTION = 0x183180
RAW_RESPONSE = 0x18E560
DM_RESPONSE = 0x183BD0
COMMAND_QUEUE = 0x18E384
ALLOC = 0x182E84
MEMCPY = 0x423CA0
TIMER = 0x18E09C
UPDATE_WINDOW = 0x18E538
VENDOR_CALLBACK = 0x3D000200
HAL_WRITE = 0x3D000400
HAL_TABLE = 0x3D003000
INPUT_TLVS = 0x3D004000
RESPONSE_MESSAGE = 0x3D001800
GET_ROUTING = bytes.fromhex('210200')


class NciMachine(OfflineMachine):
    def __init__(self, image, *, capture_builder=False, fail_allocation=False):
        self.capture_builder = capture_builder
        self.fail_allocation = fail_allocation
        self.allocations, self.commands, self.responses, self.timers = [], [], [], []
        self.next_allocation = 0x3D005000
        self.window_updates = 0
        super().__init__(image)
        self.write32(NFC + 0x1D4, HAL_TABLE)
        self.write32(HAL_TABLE + 0x18, HAL_WRITE | 1)
        self.cpu.mem_write(NFC + 0x1CF, b'\x01')  # Command window available.

    def _trace(self, cpu, address, size, data):
        r0, r1, r2, *_ = self.regs
        if address == BASE + ALLOC:
            length = cpu.reg_read(r0)
            if self.fail_allocation:
                self._return(0)
                return
            pointer = self.next_allocation
            self.next_allocation += (length + 31) & ~15
            self.allocations.append({'size': length, 'pointer': hex(pointer)})
            self._return(pointer)
        elif address == BASE + MEMCPY:
            dst, src, length = (cpu.reg_read(r) for r in (r0, r1, r2))
            cpu.mem_write(dst, bytes(cpu.mem_read(src, length)))
            self._return(dst)
        elif address == BASE + TIMER:
            self.timers.append([cpu.reg_read(r) for r in (r0, r1, r2)])
            self._return()
        elif address == BASE + UPDATE_WINDOW:
            self.window_updates += 1
            self._return()
        elif address == VENDOR_CALLBACK:
            length, pointer = cpu.reg_read(r1), cpu.reg_read(r2)
            self.responses.append({'event': cpu.reg_read(r0),
                                   'packet_hex': bytes(cpu.mem_read(pointer, length)).hex()})
            self._return()
        elif address == HAL_WRITE:
            length, pointer = cpu.reg_read(r0), cpu.reg_read(r1)
            self.commands.append(bytes(cpu.mem_read(pointer, length)).hex())
            self._return()
        elif address == BASE + 0x18E25C and self.capture_builder:
            pointer = cpu.reg_read(r0)
            _, length, offset, _ = struct.unpack('<4H', bytes(cpu.mem_read(pointer, 8)))
            self.commands.append(bytes(cpu.mem_read(pointer + 8 + offset, length)).hex())
            self._return()
        else:
            super()._trace(cpu, address, size, data)

    def build_route_packet(self, more, count, tlvs):
        from unicorn.arm_const import UC_ARM_REG_R3
        self.cpu.mem_write(INPUT_TLVS, tlvs or b'\x00')
        self.cpu.reg_write(UC_ARM_REG_R3, INPUT_TLVS)
        return self.call(SET_ROUTING, more, count, len(tlvs))


def verify(image):
    guard(image)
    for offset, expected in {
        SET_ROUTING: '2de9f047951c',
        RAW_ACTION: '0c2280b58280',
        RAW_RESPONSE: 'b0b50e4c00af',
        DM_RESPONSE: '2de9f0410a28',
        COMMAND_QUEUE: '2de9f0470546',
    }.items():
        if image[offset:offset+len(bytes.fromhex(expected))] != bytes.fromhex(expected):
            raise ValueError(f'3.201.79 NCI ABI mismatch at {offset:#x}')
    assert struct.unpack_from('<I', image, 0x6F459C + 26*4)[0] == BASE + RAW_ACTION + 1
    cases = []
    # Opaque synthetic eSE entry plus an exact test AID -> DH entry. These are
    # examples only, never a claimed snapshot of the user's real controller.
    baseline = bytes.fromhex('0103c00104')
    test_route = bytes.fromhex('02090001f0010203040506')
    for name, more, count, tlvs, expected in (
        ('synthetic_baseline_and_test_aid', 0, 2, baseline+test_route,
         '2101120002'+(baseline+test_route).hex()),
        ('synthetic_original_table_restore', 0, 1, baseline, '2101070001'+baseline.hex()),
        ('segmented_routing_more_flag', 1, 1, baseline, '2101070101'+baseline.hex()),
        ('empty_routing_is_destructive_not_a_query', 0, 0, b'', '2101020000'),
    ):
        m = NciMachine(image, capture_builder=True)
        assert m.build_route_packet(more, count, tlvs) == 0
        assert m.commands == [expected]
        cases.append({'case': name, 'passed': True, 'emulated_command_hex': expected})
    m = NciMachine(image, capture_builder=True, fail_allocation=True)
    assert m.build_route_packet(0, 1, baseline) == 3 and not m.commands
    cases.append({'case': 'route_builder_allocation_failure', 'passed': True})

    m = NciMachine(image)
    message = bytearray(24)
    struct.pack_into('<H', message, 0, 0x11A)
    struct.pack_into('<I', message, 8, VENDOR_CALLBACK | 1)
    message[13] = len(GET_ROUTING)
    struct.pack_into('<I', message, 16, MESSAGE+20)
    message[20:23] = GET_ROUTING
    m.cpu.mem_write(MESSAGE, bytes(message))
    assert m.call(0x181334, MESSAGE) == 0
    assert m.commands == [GET_ROUTING.hex()] and m.freed == [MESSAGE]
    assert m.read32(NFC + 0x140) == VENDOR_CALLBACK | 1
    assert bytes(m.cpu.mem_read(NFC + 0xBC, 1)) == b'\x01'
    assert {BASE + RAW_ACTION, BASE + COMMAND_QUEUE} <= m.executed
    cases.append({'case': 'read_only_get_routing_through_original_dm_and_nci_queue',
                  'passed': True, 'emulated_command_hex': GET_ROUTING.hex(),
                  'original_queue_frees_message_once': True})
    # Original response decoder invokes callback, then clears its pointer and
    # flag. The window/timer completion remains an explicit emulator boundary.
    response = bytes.fromhex('41020100')
    m.cpu.mem_write(RESPONSE_MESSAGE, struct.pack('<4H', 0x4000, len(response), 1, 0))
    m.cpu.mem_write(RESPONSE_MESSAGE+9, response)
    m.call(RAW_RESPONSE, RESPONSE_MESSAGE)
    assert m.responses == [{'event': 0x42, 'packet_hex': response.hex()}]
    assert m.read32(NFC + 0x140) == 0
    assert bytes(m.cpu.mem_read(NFC + 0xBC, 1)) == b'\x00'
    assert m.window_updates == 1
    cases.append({'case': 'raw_response_callback_returns_then_pointer_is_cleared',
                  'passed': True, 'response': m.responses})

    m = NciMachine(image)
    before = bytes(m.cpu.mem_read(DM, 0x200))
    m.call(DM_RESPONSE, 0x500B, EVENT_DATA)
    assert not m.callbacks and before == bytes(m.cpu.mem_read(DM, 0x200))
    cases.append({'case': 'ordinary_get_routing_response_event_is_ignored', 'passed': True})
    return {'execution': 'OFFLINE_ORIGINAL_ARM_CODE_WITH_SYNTHETIC_RAM',
            'firmware': 'S4 3.201.79', 'real_controller_query': False,
            'real_route_backup': False, 'real_route_write': False,
            'cases_passed': len(cases), 'cases': cases,
            'boundaries': ['GKI allocation/free', 'memcpy', 'HAL output', 'timers',
                           'application callback', 'routing builder send boundary',
                           'command-window update after response'],
            'not_verified': ['RF_GET_ROUTING notification capture',
                             'live command queue idle state and timeout cleanup',
                             'controller supports DH AID routing', 'test-AID RF round trip']}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    result = verify(a.firmware.read_bytes())
    if a.output:
        a.output.parent.mkdir(parents=True, exist_ok=True)
        a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
