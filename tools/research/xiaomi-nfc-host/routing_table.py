#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Parse complete NCI GET_ROUTING notifications and plan a test-only AID route.

Offline only. Opaque original TLVs and their order/power bytes are preserved.
This emits command bytes for review, never connects to or writes to a device.
"""
import argparse
import json
from pathlib import Path

TEST_AID = bytes.fromhex('F0010203040506')
TEST_ROUTE = bytes((2, len(TEST_AID)+2, 0, 1))+TEST_AID


def parse_notifications(frames):
    if not frames:
        raise ValueError('no routing notifications')
    entries = []
    for index, frame in enumerate(frames):
        if len(frame) < 5 or frame[:2] != bytes.fromhex('6102'):
            raise ValueError('expected complete unfragmented NCI RF_GET_ROUTING notification')
        if frame[2]+3 != len(frame):
            raise ValueError('NCI length mismatch')
        more, count = frame[3:5]
        if more not in (0, 1) or more != (index != len(frames)-1):
            raise ValueError('missing final notification or trailing data after final')
        offset = 5
        for _ in range(count):
            if offset+2 > len(frame):
                raise ValueError('routing TLV header truncated')
            length = frame[offset+1]
            end = offset+2+length
            if end > len(frame):
                raise ValueError('routing TLV value truncated')
            entries.append(frame[offset:end])
            offset = end
        if offset != len(frame):
            raise ValueError('routing count does not cover complete payload')
    return entries


def describe(entry):
    tag, length = entry[:2]
    value = entry[2:]
    out = {'raw_tlv_hex': entry.hex(), 'type': tag, 'value_length': length}
    if tag in (0, 1) and length == 3:
        out.update({'kind': 'technology' if tag == 0 else 'protocol',
                    'destination': value[0], 'power_state_raw': value[1],
                    'technology' if tag == 0 else 'protocol': value[2]})
    elif tag == 2 and 7 <= length <= 18:
        out.update({'kind': 'aid', 'destination': value[0],
                    'power_state_raw': value[1], 'aid_hex': value[2:].hex()})
    else:
        out['kind'] = 'opaque_preserved'
    return out


def set_routing_command(entries):
    raw = b''.join(entries)
    if len(entries) > 255 or len(raw) > 253:
        raise ValueError('requires validated segmented SET_ROUTING; single packet refused')
    if any(len(x) < 2 or len(x) != x[1]+2 for x in entries):
        raise ValueError('invalid complete routing TLV')
    return bytes((0x21, 1, len(raw)+2, 0, len(entries)))+raw


def plan(frames):
    entries = parse_notifications(frames)
    # For a write experiment, avoid guessing how unknown route tags/flags could
    # interact with AID matching. They remain valid opaque backup information.
    if any(describe(x)['kind'] == 'opaque_preserved' for x in entries):
        raise ValueError('opaque route interaction not understood; preserve backup, refuse test plan')
    if any(x[0] == 2 and x[4:] == TEST_AID for x in entries):
        raise ValueError('test AID already present; no ownership for replacing it')
    # Modern AOSP/NXP nfa_ee_lmrt_to_nfcc emits AID before protocol/technology.
    # S4 rejected an AID-last full table with FAILED(03), then exposed an empty
    # table; that attempt was restored. Keep original relative order/bytes.
    proposed = [TEST_ROUTE]+entries
    return {'execution': 'OFFLINE_COMMAND_PLAN_NO_DEVICE_WRITE',
            'original_notifications_hex': [x.hex() for x in frames],
            'original_entries': [describe(x) for x in entries],
            'original_tlvs_hex': b''.join(entries).hex(),
            'test_aid_hex': TEST_AID.hex(), 'test_destination': 'DH 0x00',
            'test_power_state': 1, 'proposed_tlvs_hex': b''.join(proposed).hex(),
            'install_command_hex': set_routing_command(proposed).hex(),
            'restore_command_hex': set_routing_command(entries).hex(),
            'routing_order': 'new test AID first; original entries retain relative order',
            'original_tlvs_and_order_preserved': proposed[1:] == entries,
            'live_prerequisites': ['same original table immediately before write',
                                   'controller AID routing feature and capacity verified',
                                   'correct RF state and original discovery restoration',
                                   'readback of appended table, then exact original restoration'],
            'real_test_aid_registered': False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture', type=Path, help='sanitized capture JSON with routing_notifications_hex')
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    capture = json.loads(a.capture.read_text())
    if 'header' in capture:
        h = capture['header']
        if h[8] != 1 or h[9] or h[7]:
            p.error('capture incomplete, malformed or still active')
    result = plan([bytes.fromhex(x) for x in capture['routing_notifications_hex']])
    if a.output:a.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':main()
