#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE Unicorn ARM verification for host-relay-session, host-relay-pump, and relay-route-write."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import verify_host_session as host
from verify_rf_data import BASE, DM

host.TEXT = 0x3D006000
DATA = host.DATA
REQUEST = '/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-request.txt'
RESPONSE = '/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-response.txt'
STOP_FILE = '/data/quickapp/files/com.meta.s4nativeprobe/relay-stop.txt'
ACTIVATION = bytes.fromhex('61050b0102048001000000000000')
TUNION_SELECT = bytes.fromhex('00a4040008a00000063201010500')
READ_BINARY = bytes.fromhex('00b0950000')
LONG_FCI_RAPDU = ('6f208408a000000632010105a5149f0c0f000000000000000000000000008213' + '00' * 20 + '9000')
CARD_INFO_RAPDU = ('31000000000000008213' + '11' * 20 + '9000')


class RelayMachine(host.HostMachine):
    def __init__(self, image, probe, controls):
        super().__init__(image, probe, controls)
        self.files = {}
        self.fds = {}
        self.fd = 0
        self.stop_requested = False

    def ipc(self):
        return list(struct.unpack('<16I', bytes(self.cpu.mem_read(DATA + 296, 64))))

    def string(self, p):
        raw = bytes(self.cpu.mem_read(p, 200))
        return raw.split(b'\0', 1)[0].decode()

    def _trace(self, cpu, a, size, data):
        x, y, z = [cpu.reg_read(r) for r in self.regs[:3]]
        if a == 0x2C55D838:
            path = self.string(x)
            assert path in (REQUEST, RESPONSE, STOP_FILE)
            if path == STOP_FILE:
                self._return(0 if self.stop_requested else 0xFFFFFFFF)
                if self.stop_requested:
                    self.fds[self.fd] = path
                    self.fd += 1
                return
            if y == 1 and path not in self.files:
                self._return(0xFFFFFFFF)
                return
            if y == 0x26:
                self.files[path] = b''
            self.fds[self.fd] = path
            self._return(self.fd)
            self.fd += 1
        elif a == 0x2C55D61A:
            assert self.fds[x] == REQUEST
            value = bytes(cpu.mem_read(y, z))
            self.files[REQUEST] = value
            self._return(z)
        elif a == 0x2C55D5E0:
            assert self.fds[x] == RESPONSE
            value = self.files[RESPONSE][:z]
            cpu.mem_write(y, value)
            self._return(len(value))
        elif a == 0x2C55AF94:
            del self.fds[x]
            self._return(0)
        elif a == 0x2C20DC10:
            assert x == 5000
            self._return(0)
        else:
            super()._trace(cpu, a, size, data)

    def active(self):
        self.armed()
        self.cpu.mem_write(DM + 0x4C, b'\x05')
        self.cpu.mem_write(DM + 0x52, b'\xff\xff')
        self.hal_frame(ACTIVATION)

    def send_capdu(self, apdu):
        self.hal_frame(bytes([0, 0, len(apdu)]) + apdu)

    def pump(self):
        self.call(self.ipc()[2] - BASE - 1, instruction_bound=50000)

    def write_reply(self, rapdu_hex, seq=None, epoch=None):
        ipc = self.ipc()
        s = ipc[6] if seq is None else seq
        e = ipc[7] if epoch is None else epoch
        self.files[RESPONSE] = f'S4R1:{s}:{e}:{rapdu_hex}'.encode()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    p.add_argument('--llvm', type=Path, required=True)
    p.add_argument('--build-dir', type=Path, required=True)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    image = a.firmware.read_bytes()

    def link(stem, text, data):
        b = a.build_dir / (stem + '-relay-offline')
        ld = b.with_suffix('.ld')
        elf = b.with_suffix('.elf')
        binary = b.with_suffix('.bin')
        ld.write_text(
            f'SECTIONS {{ . = {text:#x}; .text : {{ *(.text .text.*) *(.rodata*) }}\n'
            f'. = {data:#x}; .data : {{ *(.data.result) *(.data .data.*) }} .bss : {{ *(.bss*) }}\n'
            '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n'
        )
        subprocess.run([str(a.llvm / 'ld.lld'), '-T', str(ld), '--entry=module_initialize',
                        str(a.build_dir / (stem + '.o')), '-o', str(elf)], check=True)
        subprocess.run([str(a.llvm / 'llvm-objcopy'), '-O', 'binary', '--only-section=.text',
                        '--only-section=.data', str(elf), str(binary)], check=True)
        return binary.read_bytes()

    probe = link('host-relay-session', host.TEXT, DATA)
    controls = {x: link('host-test-' + x, host.CONTROL, host.CONTROL_DATA) for x in ('arm', 'remove', 'stop')}
    pump = link('host-relay-pump', host.CONTROL, host.CONTROL_DATA)
    cases = []

    def passed(name):
        cases.append({'case': name, 'passed': True})

    m = RelayMachine(image, probe, controls)
    m.active()

    # 1. First APDU in activation: T-Union SELECT -> long RAPDU (> 32 bytes)
    m.send_capdu(TUNION_SELECT)
    assert m.ipc()[4] == 1 and m.ipc()[6] == 1
    m.pump()
    req1 = json.loads(m.files[REQUEST])
    assert req1 == {'relay': 's4', 'op': 'apdu', 'seq': 1, 'epoch': m.ipc()[7], 'apdu': TUNION_SELECT.hex()}
    m.write_reply(LONG_FCI_RAPDU)
    m.pump()
    assert m.ipc()[4] == 0 and len(m.enqueued) == 1
    m.call(0x181334, m.enqueued.pop(0))
    assert m.queue[-1]['payload_hex'] == LONG_FCI_RAPDU
    passed('select_tunion_forwards_arbitrary_capdu_and_long_rapdu')

    # 2. Second APDU in the SAME activation (multi-APDU tap!)
    m.send_capdu(READ_BINARY)
    assert m.ipc()[4] == 1 and m.ipc()[6] == 2
    m.pump()
    req2 = json.loads(m.files[REQUEST])
    assert req2 == {'relay': 's4', 'op': 'apdu', 'seq': 2, 'epoch': m.ipc()[7], 'apdu': READ_BINARY.hex()}
    m.write_reply(CARD_INFO_RAPDU)
    m.pump()
    assert m.ipc()[4] == 0 and len(m.enqueued) == 1
    m.call(0x181334, m.enqueued.pop(0))
    assert m.queue[-1]['payload_hex'] == CARD_INFO_RAPDU
    passed('multi_apdu_same_activation_increments_seq_and_replies')

    # 3. Fourth APDU (> old seq 3 cap!) still works
    m.send_capdu(bytes.fromhex('805c000204'))
    m.pump()
    m.write_reply('000009c49000')
    m.pump()
    m.call(0x181334, m.enqueued.pop(0))
    assert m.queue[-1]['payload_hex'] == '000009c49000'
    m.send_capdu(bytes.fromhex('0084000008'))
    m.pump()
    m.write_reply('11223344556677889000')
    m.pump()
    m.call(0x181334, m.enqueued.pop(0))
    assert m.ipc()[6] == 4 and m.queue[-1]['payload_hex'] == '11223344556677889000'
    passed('unlimited_seq_beyond_old_cap_of_3')

    # 4. Late reply after deactivation is rejected
    m.send_capdu(READ_BINARY)
    m.pump()
    stale_seq, stale_epoch = m.ipc()[6], m.ipc()[7]
    m.hal_frame(bytes.fromhex('6106020000'))
    m.write_reply('9000', seq=stale_seq, epoch=stale_epoch)
    m.pump()
    assert not m.enqueued and not m.fds
    passed('deactivation_rejects_late_reply')

    # 5. Stop file causes host-relay-pump to exit immediately
    m2 = RelayMachine(image, probe, controls)
    m2.active()
    m2.stop_requested = True
    m2.cpu.mem_write(host.CONTROL, pump)
    m2.cpu.ctl_remove_cache(host.CONTROL, host.CONTROL + 0x1000)
    m2.write32(host.CONTROL_DATA + 64, DATA)
    m2.call(host.CONTROL - BASE, instruction_bound=50000)
    assert m2.read32(host.CONTROL_DATA + 4) == 3 and m2.read32(host.CONTROL_DATA + 8) == 0
    passed('stop_file_exits_pump_immediately')

    result = {
        'execution': 'OFFLINE_COMPILED_HOST_RELAY_AND_ORIGINAL_SEND',
        'cases_passed': len(cases),
        'cases': cases,
        'real_RF_SPP_roundtrip': False,
    }
    if a.output:
        a.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
