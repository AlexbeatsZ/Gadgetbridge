#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run retained S4 RF receive/send ARM code with synthetic RAM, OFFLINE ONLY.

The NFCC send queue, application callback, GKI free and pending-data drain are
explicit emulator boundaries. No watch, Bluetooth, NFC fd or real card is used.
Passing this is ABI evidence, not external-reader RF or SPP acceptance.
"""
import argparse
import json
from pathlib import Path
import struct

BASE = 0x2C1C0000
DM = 0x3C2D46FC
NFC = 0x3C2D4B8C
CE = 0x3C2D4628
DM_DISPATCH = 0x181334
RAW_SEND = 0x1820D0
RF_RECEIVE = 0x18319E
RF_SET_CALLBACK = 0x18DFF0
RF_CURRENT_SINK = 0x18472C
SEND_DATA = 0x18E6D4
FIND_CONNECTION = 0x18E5C4
SEND_QUEUE = 0x18E0D4
FREE_BUFFER = 0x18311C
DRAIN_PENDING = 0x18CEC0
STOP, CALLBACK, OLD_CALLBACK = 0x3D000000, 0x3D000100, 0x3D000180
MESSAGE, EVENT_DATA = 0x3D000800, 0x3D000600
SELECT = bytes.fromhex('00A4040007F001020304050600')
RESPONSE = b'S4TEST' + bytes.fromhex('9000')


def guard(image):
    checks = {
        0x18134C: struct.pack('<I', BASE + 0x6F459C),
        0x6F45CC: struct.pack('<I', BASE + RAW_SEND + 1),
        DM_DISPATCH: bytes.fromhex('80b5037800af1b2b'),
        RAW_SEND: bytes.fromhex('b0b5194b0146'),
        RF_RECEIVE: bytes.fromhex('46f2030390b59942'),
        RF_SET_CALLBACK: bytes.fromhex('80b5044b00af43f8040f'),
        RF_CURRENT_SINK: bytes.fromhex('46f2040380b59942'),
        SEND_DATA: bytes.fromhex('b0b500af0d46'),
        FIND_CONNECTION: bytes.fromhex('90b5fe2800af'),
    }
    for offset, expected in checks.items():
        if image[offset:offset + len(expected)] != expected:
            raise ValueError(f'3.201.79 RF ABI differs at file offset {offset:#x}')


class OfflineMachine:
    def __init__(self, image, queue_status=0):
        from unicorn import (Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS,
                             UC_HOOK_CODE, UC_PROT_READ, UC_PROT_EXEC)
        from unicorn.arm_const import (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2,
                                       UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC)
        self.regs = (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2,
                     UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC)
        self.cpu = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        self.cpu.mem_map(BASE, (len(image) + 4095) & ~4095, UC_PROT_READ | UC_PROT_EXEC)
        self.cpu.mem_write(BASE, image)
        self.cpu.mem_map(0x3C200000, 0x400000)
        self.cpu.mem_map(0x3D000000, 0x10000)
        self.cpu.mem_write(CE, bytes([0xA5]) * 0xD4)
        self.ce_before = bytes(self.cpu.mem_read(CE, 0xD4))
        self.write32(DM + 0x24, CALLBACK | 1)
        self.write32(NFC + 4, OLD_CALLBACK | 1)
        self.cpu.mem_write(NFC + 0x94, b'\x01')  # RF conn0 -> synthetic conn_cb[0].
        self.callbacks, self.queue, self.freed, self.drained, self.executed = [], [], [], [], set()
        self.queue_status = queue_status
        self.cpu.hook_add(UC_HOOK_CODE, self._trace)

    def write32(self, address, value):
        self.cpu.mem_write(address, struct.pack('<I', value))

    def read32(self, address):
        return struct.unpack('<I', bytes(self.cpu.mem_read(address, 4)))[0]

    def packet(self, payload, offset=4):
        self.cpu.mem_write(MESSAGE, struct.pack('<4H', 0x10C, len(payload), offset, 0))
        self.cpu.mem_write(MESSAGE + 8 + offset, payload)

    def _return(self, result=None):
        r0, _, _, _, lr, pc = self.regs
        if result is not None:
            self.cpu.reg_write(r0, result)
        self.cpu.reg_write(pc, self.cpu.reg_read(lr))

    def _trace(self, cpu, address, _size, _data):
        r0, r1, r2, _, _, _ = self.regs
        self.executed.add(address)
        if address == STOP:
            cpu.emu_stop()
        elif address == CALLBACK:
            data = cpu.reg_read(r1)
            pointer, length = self.read32(data + 4), struct.unpack('<H', bytes(cpu.mem_read(data + 8, 2)))[0]
            self.callbacks.append({'event': cpu.reg_read(r0),
                                   'status': bytes(cpu.mem_read(data, 1))[0],
                                   'payload_hex': bytes(cpu.mem_read(pointer, length)).hex()})
            self._return()
        elif address == BASE + FREE_BUFFER:
            self.freed.append(cpu.reg_read(r0))
            self._return()
        elif address == BASE + DRAIN_PENDING:
            self.drained.append(cpu.reg_read(r0))
            self._return()
        elif address == BASE + SEND_QUEUE:
            msg = cpu.reg_read(r1)
            _, length, offset, _ = struct.unpack('<4H', bytes(cpu.mem_read(msg, 8)))
            self.queue.append({'connection_cb': hex(cpu.reg_read(r0)),
                               'payload_hex': bytes(cpu.mem_read(msg + 8 + offset, length)).hex(),
                               'bt_hdr_offset': offset})
            self._return(self.queue_status)

    def call(self, offset, r0=0, r1=0, r2=0, *, instruction_bound=10000):
        a, b, c, sp, lr, _ = self.regs
        for reg, value in ((a, r0), (b, r1), (c, r2), (sp, 0x3D00F000), (lr, STOP | 1)):
            self.cpu.reg_write(reg, value)
        self.cpu.emu_start(BASE + offset + 1, STOP, count=instruction_bound)
        if self.cpu.reg_read(self.regs[-1]) != STOP:
            raise AssertionError('original code did not return within the offline instruction bound')
        if bytes(self.cpu.mem_read(CE, 0xD4)) != self.ce_before:
            raise AssertionError('synthetic eSE CE control was modified')
        return self.cpu.reg_read(a)


def receive_case(image, *, null_buffer=False, event=0x6003):
    m = OfflineMachine(image)
    m.packet(SELECT)
    m.cpu.mem_write(EVENT_DATA, struct.pack('<II', 0, 0 if null_buffer else MESSAGE))
    m.call(RF_RECEIVE, r1=event, r2=EVENT_DATA)
    return m


def send_case(image, *, state=5, offset=4, queue_status=0, connected=True):
    m = OfflineMachine(image, queue_status)
    m.packet(RESPONSE, offset)
    m.cpu.mem_write(DM + 0x4C, bytes([state]))
    m.cpu.mem_write(NFC + 0x131, b'\x01')  # Initial reassembly flag.
    if not connected:
        m.cpu.mem_write(NFC + 0x94, b'\x00')
    result = m.call(DM_DISPATCH, r0=MESSAGE)
    return m, result


def verify(image):
    guard(image)
    rows = []
    m = receive_case(image)
    assert m.callbacks == [{'event': 9, 'status': 0, 'payload_hex': SELECT.hex()}]
    assert m.freed == [MESSAGE]
    rows.append({'case': 'test_select_receive', 'passed': True, 'callbacks': m.callbacks,
                 'original_buffer_freed_exactly_once': True})
    m = OfflineMachine(image)
    m.packet(SELECT)
    m.cpu.mem_write(EVENT_DATA, struct.pack('<II', 0, MESSAGE))
    m.call(RF_CURRENT_SINK, r1=0x6003, r2=EVENT_DATA)
    assert not m.callbacks and m.freed == [MESSAGE]
    rows.append({'case': 'live_pointer_target_drops_rf_data', 'passed': True,
                 'original_buffer_freed_exactly_once': True})
    m = receive_case(image, null_buffer=True)
    assert not m.callbacks and not m.freed
    rows.append({'case': 'null_receive_buffer', 'passed': True})
    m = receive_case(image, event=0x6002)
    assert m.read32(NFC + 4) == 0 and m.drained == [NFC + 4] and not m.callbacks
    rows.append({'case': 'deactivate_clears_static_callback', 'passed': True})
    m = OfflineMachine(image)
    m.call(RF_SET_CALLBACK, CALLBACK | 1)
    assert m.read32(NFC + 4) == CALLBACK | 1 and m.drained == [NFC + 4]
    m.call(RF_SET_CALLBACK, OLD_CALLBACK | 1)
    assert m.read32(NFC + 4) == OLD_CALLBACK | 1
    rows.append({'case': 'callback_install_restore', 'passed': True,
                 'pending_data_drain_boundary_called': len(m.drained)})
    m, result = send_case(image)
    assert result == 0 and m.queue == [{'connection_cb': hex(NFC + 4),
                                       'payload_hex': RESPONSE.hex(), 'bt_hdr_offset': 4}]
    assert not m.freed and m.read32(DM) & 0x800
    assert bytes(m.cpu.mem_read(NFC + 0x131, 1)) == b'\x00'
    assert {BASE + RAW_SEND, BASE + SEND_DATA, BASE + FIND_CONNECTION} <= m.executed
    rows.append({'case': 'listen_fixed_response_send', 'passed': True,
                 'queued_to_emulated_nci_boundary': m.queue,
                 'dispatcher_must_not_free_buffer': True})
    m, result = send_case(image, queue_status=3)
    assert result == 0 and m.freed == [MESSAGE]
    assert bytes(m.cpu.mem_read(NFC + 0x131, 1)) == b'\x01'
    rows.append({'case': 'send_queue_error_frees_once_restores_reassembly', 'passed': True})
    m, result = send_case(image, offset=3)
    assert result == 0 and not m.queue and m.freed == [MESSAGE]
    rows.append({'case': 'insufficient_bt_header_offset', 'passed': True})
    m, result = send_case(image, connected=False)
    assert result == 0 and not m.queue and m.freed == [MESSAGE]
    rows.append({'case': 'missing_rf_connection', 'passed': True})
    for state in (0, 1, 2, 3, 6):
        m, result = send_case(image, state=state)
        assert result == 1 and not m.queue and not m.freed
        rows.append({'case': f'inactive_state_{state}_caller_owns_buffer', 'passed': True})
    return {'execution': 'OFFLINE_ORIGINAL_ARM_CODE_WITH_SYNTHETIC_RAM',
            'firmware': 'S4 3.201.79', 'real_rf_test': False, 'spp_test': False,
            'test_aid': 'F0010203040506', 'cases_passed': len(rows), 'cases': rows,
            'synthetic_ese_control_unchanged_all_cases': True,
            'emulator_boundaries': ['application callback', 'GKI_freebuf',
                                   'nfc_data_event pending drain', 'nfc_ncif_send_data queue'],
            'not_verified': ['controller RF receipt/transmit', 'host AID routing registration/removal',
                             'live callback ownership/lifecycle', 'SPP latency', 'WTX']}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    result = verify(a.firmware.read_bytes())
    if a.output:
        a.output.parent.mkdir(parents=True, exist_ok=True)
        a.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
