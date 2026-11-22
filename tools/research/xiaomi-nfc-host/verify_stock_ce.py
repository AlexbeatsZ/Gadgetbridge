#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Execute the *original firmware's* CE handler against synthetic RAM offline.

Not a watch/RF test. This demonstrates the precise stock-stack rejection and
stub behavior without changing the watch's existing eSE listener or routing.
Requires isolated unicorn dependency and the already obtained 3.201.79 image.
"""
import argparse
import json
from pathlib import Path
import struct

BASE = 0x2C1C0000
TABLE = 0x6F4578
HANDLER = 0x180EB4
CONTROL = 0x3C2D4628
EXPECTED = (0x2C340EAD, 0x2C341EED, 0x2C340EAD,
            0x2C340EAD, 0x2C3418F9, 0x2C341E69)


def run(image, event, flags, aid):
    from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS, UC_HOOK_CODE
    from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC
    uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
    uc.mem_map(BASE, (len(image)+4095) & ~4095)
    uc.mem_write(BASE, image)
    uc.mem_map(0x3C200000, 0x400000)
    uc.mem_map(0x3D000000, 0x10000)
    message, callback, stop = 0x3D000200, 0x3D000100, 0x3D000000
    data = bytearray(48)
    struct.pack_into('<H', data, 0, event)
    struct.pack_into('<I', data, 8, callback | 1)
    data[12] = 1  # NFA_CE_REG_TYPE_ISO_DEP (host), never eSE type 4.
    data[24:24+len(aid)] = aid
    data[40] = len(aid)
    uc.mem_write(message, bytes(data))
    uc.mem_write(CONTROL+0xAC, struct.pack('<I', flags))
    initial = bytes(uc.mem_read(CONTROL, 0xD4))
    callbacks = []

    def trace(cpu, address, size, _):
        if address == stop:
            cpu.emu_stop()
        elif address == callback:
            pointer = cpu.reg_read(UC_ARM_REG_R1)
            result = bytes(cpu.mem_read(pointer, 4))
            callbacks.append({'event': cpu.reg_read(UC_ARM_REG_R0),
                              'status': result[0],
                              'handle': struct.unpack_from('<H', result, 2)[0]})
            cpu.reg_write(UC_ARM_REG_PC, cpu.reg_read(UC_ARM_REG_LR))

    uc.hook_add(UC_HOOK_CODE, trace)
    uc.reg_write(UC_ARM_REG_SP, 0x3D00F000)
    uc.reg_write(UC_ARM_REG_LR, stop | 1)
    uc.reg_write(UC_ARM_REG_R0, message)
    uc.emu_start(BASE+HANDLER+1, stop, count=3000)
    return {'event': hex(event), 'callbacks': callbacks,
            'dispatcher_free_buffer': uc.reg_read(UC_ARM_REG_R0),
            'ce_control_unchanged': bytes(uc.mem_read(CONTROL, 0xD4)) == initial}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    image = a.firmware.read_bytes()
    if struct.unpack_from('<6I', image, TABLE) != EXPECTED:
        p.error('firmware CE table differs; do not apply this version-specific ABI')
    if image[0x180EAC:0x180EB4] != bytes.fromhex('80b5012000af80bd'):
        p.error('firmware CE stub differs')
    rows = [run(image, event, 0x8101, bytes.fromhex('F0010203040506'))
            for event in (0x401, 0x402, 0x403)]
    assert rows[0]['callbacks'] == [{'event': 0x15, 'status': 3, 'handle': 0xFFFF}]
    assert all(r['ce_control_unchanged'] and r['dispatcher_free_buffer'] == 1 for r in rows)
    assert all(not r['callbacks'] for r in rows[1:])
    evidence = {'execution': 'OFFLINE_ORIGINAL_ARM_CODE_WITH_SYNTHETIC_RAM',
                'real_watch_registration_attempted': False, 'real_rf_test': False,
                'firmware': 'S4 3.201.79', 'synthetic_flags': '0x8101', 'results': rows}
    if a.output:
        a.output.parent.mkdir(parents=True, exist_ok=True)
        a.output.write_text(json.dumps(evidence, indent=2)+'\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
