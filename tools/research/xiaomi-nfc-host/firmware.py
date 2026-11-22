#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Offline S4 AP firmware inspection. Does not connect to or modify a device."""
import argparse
import json
import struct
from pathlib import Path


def occurrences(data, needle):
    cursor = 0
    while (cursor := data.find(needle, cursor)) >= 0:
        yield cursor
        cursor += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    parser.add_argument('--base', type=lambda s: int(s, 0), default=0x2C1C0000)
    sub = parser.add_subparsers(dest='command', required=True)
    strings = sub.add_parser('strings')
    strings.add_argument('text', nargs='+')
    refs = sub.add_parser('refs')
    refs.add_argument('address', nargs='+', type=lambda s: int(s, 0))
    dis = sub.add_parser('disasm')
    dis.add_argument('start', type=lambda s: int(s, 0), help='file offset')
    dis.add_argument('end', type=lambda s: int(s, 0), help='file offset, exclusive')
    args = parser.parse_args()
    data = args.firmware.read_bytes()
    if args.command == 'strings':
        for text in args.text:
            for off in occurrences(data, text.encode()):
                print(json.dumps({'text': text, 'offset': hex(off),
                                  'address': hex(args.base + off),
                                  'pointer_refs': [hex(r) for r in occurrences(
                                      data, struct.pack('<I', args.base + off))]}))
    elif args.command == 'refs':
        for address in args.address:
            print(json.dumps({'address': hex(address), 'pointer_refs': [hex(r) for r in
                              occurrences(data, struct.pack('<I', address))]}))
    else:
        if args.start & 1 or args.end <= args.start or args.end > len(data):
            parser.error('use even file offsets in range; clear Thumb address bit 0 before disassembly')
        from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB, CS_MODE_MCLASS
        from capstone.arm import ARM_OP_MEM, ARM_REG_PC
        cs = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_MCLASS)
        cs.detail = True
        cs.skipdata = True
        for ins in cs.disasm(data[args.start:args.end], args.base + args.start):
            note = ''
            if ins.id and ins.mnemonic.startswith('ldr'):
                for op in ins.operands:
                    if op.type == ARM_OP_MEM and op.mem.base == ARM_REG_PC:
                        literal = ((ins.address + 4) & ~3) + op.mem.disp - args.base
                        if 0 <= literal <= len(data) - 4:
                            value = struct.unpack_from('<I', data, literal)[0]
                            note = f' ; literal[{literal:#x}]={value:#x}'
                            s = value - args.base
                            if 0 <= s < len(data):
                                raw = data[s:s+160].split(b'\0', 1)[0]
                                if raw and all(32 <= b < 127 or b in (10, 13) for b in raw):
                                    note += ' ' + repr(raw.decode())
            print(f'{ins.address-args.base:08x} {ins.bytes.hex():10} {ins.mnemonic:9} {ins.op_str}{note}')


if __name__ == '__main__':
    main()
