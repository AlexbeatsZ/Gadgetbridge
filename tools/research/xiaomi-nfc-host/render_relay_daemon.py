#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Render the always-armed Watch S4 Direct-SPP NFC Relay loader watchface main.lua."""
import argparse
from pathlib import Path
import re

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--build-dir', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()

root = Path(__file__).resolve().parent
canonical = (root / 'test-route-roundtrip.lua.in').read_text()
common = canonical[canonical.index('local function command('):canonical.index('command("modules-before.txt","lsmod")')]
s = (root / 'relay-daemon.lua.in').read_text().replace('@COMMON_HELPERS@', common)

stems = [
    'get-routing-status', 'discovery-stop', 'discovery-start',
    'relay-route-append-nxp', 'relay-route-append-screenoff', 'relay-route-append',
    'relay-route-restore',
]
payloads = {stem: (a.build_dir / (stem + '.elf')).read_bytes() for stem in stems}
table = ','.join('{"' + stem + '","' + data.hex() + '"}' for stem, data in payloads.items())
s = s.replace('@PAYLOADS@', table)

daemon_data = (a.build_dir / 'host-daemon-relay.elf').read_bytes()
if daemon_data.count(bytes.fromhex('dfc0adde')) != 1:
    p.error('host-daemon-relay.elf must have exactly one route_power_state placeholder (dfc0adde)')
s = s.replace('@DAEMON_HEX@', daemon_data.hex())

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
