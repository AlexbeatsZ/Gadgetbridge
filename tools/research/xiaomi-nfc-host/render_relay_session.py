#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Render the reusable Watch S4 NFC Bluetooth relay watchface main.lua."""
import argparse
from pathlib import Path
import re

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--build-dir', type=Path, required=True)
p.add_argument('--window-seconds', type=int, default=180)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if not 10 <= a.window_seconds <= 300:
    p.error('window-seconds 10..300 required')

root = Path(__file__).resolve().parent
canonical = (root / 'test-route-roundtrip.lua.in').read_text()
common = canonical[canonical.index('local function command('):canonical.index('command("modules-before.txt","lsmod")')]
s = (root / 'relay-session.lua.in').read_text().replace('@COMMON_HELPERS@', common).replace('@WINDOW@', str(a.window_seconds))

stems = [
    'get-routing-capture', 'get-routing-status', 'discovery-stop',
    'discovery-start', 'relay-route-append', 'relay-route-restore',
    'host-relay-session',
]
payloads = {stem: (a.build_dir / (stem + '.elf')).read_bytes() for stem in stems}
table = ','.join('{"' + stem + '","' + data.hex() + '"}' for stem, data in payloads.items())
s = s.replace('@PAYLOADS@', table)

pump = (a.build_dir / 'host-relay-pump.elf').read_bytes()
if pump.count(bytes.fromhex('dec0adde')) != 1:
    p.error('host-relay-pump needs exactly one binding')
s = s.replace('@PUMP_HEX@', pump.hex())

tags = {'ARM': 'host-test-arm', 'REMOVE': 'host-test-remove', 'STOP_OWNED': 'host-test-stop'}
for tag, stem in tags.items():
    data = (a.build_dir / (stem + '.elf')).read_bytes()
    if data.count(bytes.fromhex('dec0adde')) != 1:
        p.error(f'{stem} must have exactly one invalid-default binding')
    s = s.replace('@' + tag + '_HEX@', data.hex())

if re.search(r'@[A-Z_]+@', s):
    p.error('unresolved placeholder')

a.output.write_text(s)
print(f'Rendered {len(s)} bytes to {a.output}')
