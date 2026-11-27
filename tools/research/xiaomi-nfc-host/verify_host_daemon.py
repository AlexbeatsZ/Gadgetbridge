#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Offline Unicorn verification of host-daemon-relay.c against the S4 3.201.79 ROM."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import verify_host_session as host
from verify_rf_data import BASE, DM

host.TEXT = 0x3D006000
DATA = host.DATA
HAL_SLOT = 0x3C2D45CC
TOPICS_HEAD = 0x3C2FE464
LOOP_PTR = 0x3C2FE57C
ORIG_PB_RECV = 0x2C480B21

SEM_INIT = 0x2C1FF7FC
SEM_WAIT = 0x2C1F15B8
SEM_POST = 0x2C1F1612
SEM_DESTROY = 0x2C1F1590
PTHREAD_CREATE = 0x2C20732C
UV_ASYNC_INIT = 0x2C254FA0
UV_ASYNC_SEND = 0x2C254428
UV_CLOSE = 0x2C255700
QUICKAPP_SEND = 0x2C47E918

ACTIVATION = bytes.fromhex('61050b0102048001000000000000')
TUNION_SELECT = bytes.fromhex('00a4040008a00000063201010500')
LONG_FCI_RAPDU = '6f248408a000000632010105a518500a4348494e4154554e494f870101bf0c069f0c030102039000'
READ_BINARY = bytes.fromhex('00b095001e')
CARD_INFO_RAPDU = '0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e9000'


class DaemonMachine(host.HostMachine):
    def __init__(self, firmware, probe, controls):
        super().__init__(firmware, probe, controls)
        try:
            self.cpu.mem_read(TOPICS_HEAD, 4)
        except Exception:
            self.cpu.mem_map(0x3C2FE000, 0x2000)
        self.fake_loop = 0x3C2FE600
        self.topic_node = 0x3C2FE700
        self.sub_node = 0x3C2FE780
        self.ev_buf = 0x3C2FE800
        self.msg_buf = 0x3C2FE880
        self.pkg_buf = 0x3C2FE900
        self.bytes_buf = 0x3C2FE980

        self.cpu.mem_write(LOOP_PTR, struct.pack('<I', self.fake_loop))
        self.cpu.mem_write(TOPICS_HEAD, struct.pack('<II', self.topic_node, self.topic_node))
        self.cpu.mem_write(self.topic_node, struct.pack('<II', TOPICS_HEAD, TOPICS_HEAD))
        # kind=0 @ +8, dir=0 @ +0xc, type=0x14 @ +0xd, subtype=8 @ +0xe
        self.cpu.mem_write(self.topic_node + 8, bytes([0, 0, 0, 0, 0, 0x14, 8, 0]))
        shead = self.topic_node + 0x28
        self.cpu.mem_write(shead, struct.pack('<II', self.sub_node, self.sub_node))
        self.cpu.mem_write(self.sub_node, struct.pack('<IIII', shead, shead, ORIG_PB_RECV, 0x12345678))

        self.sem_inited = False
        self.sem_posts = 0
        self.async_cb = 0
        self.async_handle = 0
        self.async_closed_cb = 0
        self.worker_fn = 0
        self.sent_wear = []
        self.orig_recv_calls = 0

    def read_cstr(self, addr):
        out = bytearray()
        while len(out) < 128:
            b = self.cpu.mem_read(addr + len(out), 1)[0]
            if b == 0:
                break
            out.append(b)
        return out.decode('ascii', errors='replace')

    def _trace(self, cpu, address, size, data):
        r0, r1, r2 = [cpu.reg_read(r) for r in self.regs[:3]]
        if address == SEM_INIT:
            self.sem_inited = True
            self._return(0)
        elif address == SEM_POST:
            assert self.sem_inited, 'sem_post before sem_init'
            self.sem_posts += 1
            self._return(0)
        elif address == UV_ASYNC_INIT:
            assert r0 == self.fake_loop
            self.async_handle = r1
            self.async_cb = r2
            self._return(0)
        elif address == UV_ASYNC_SEND:
            self._return(0)
        elif address == UV_CLOSE:
            self.async_closed_cb = r1
            self._return()
        elif address == PTHREAD_CREATE:
            self.worker_fn = r2
            cpu.mem_write(r0, struct.pack('<I', 42))
            self._return(0)
        elif address == QUICKAPP_SEND:
            pkg = self.read_cstr(r0)
            payload = bytes(cpu.mem_read(r1, r2)).decode('ascii')
            self.sent_wear.append((pkg, payload))
            self._return()
        elif address == (ORIG_PB_RECV & ~1):
            self.orig_recv_calls += 1
            self._return()
        else:
            super()._trace(cpu, address, size, data)

    def ipc(self):
        return list(struct.unpack('<16I', bytes(self.cpu.mem_read(DATA + 296, 64))))

    def sub_cb(self):
        return struct.unpack('<I', bytes(self.cpu.mem_read(self.sub_node + 8, 4)))[0]

    def deliver_pb(self, pkg, payload_str):
        payload = payload_str.encode('ascii')
        self.cpu.mem_write(self.pkg_buf, pkg.encode('ascii') + b'\x00')
        self.cpu.mem_write(self.bytes_buf, struct.pack('<H', len(payload)) + payload)
        self.cpu.mem_write(self.msg_buf, bytes([0x14, 8]) + b'\x00' * 14 +
                           struct.pack('<III', self.pkg_buf, 0, self.bytes_buf))
        self.cpu.mem_write(self.ev_buf, b'\x00' * 0x18 + struct.pack('<I', self.msg_buf))
        hook = self.sub_cb()
        assert hook != ORIG_PB_RECV, 'pb_recv_hook not installed'
        self.call(hook - BASE - 1, self.ev_buf, 0x12345678, instruction_bound=50000)

    def run_async_cb(self):
        assert self.async_cb != 0
        self.call(self.async_cb - BASE - 1, self.async_handle, instruction_bound=50000)

    def arm_and_activate(self):
        self.registered()
        assert self.sub_cb() == ORIG_PB_RECV  # not patched until arm!
        assert self.ipc()[15] == self.sub_node
        self.cpu.mem_write(DM + 0x4A, struct.pack('<HB', 1, 1))
        self.write32(DM + 0x7C, 0x40000)
        self.control('arm')
        assert self.header()[1] == 4
        assert self.sub_cb() != ORIG_PB_RECV
        self.cpu.mem_write(DM + 0x4C, b'\x05')
        self.cpu.mem_write(DM + 0x52, b'\xff\xff')
        self.hal_frame(ACTIVATION)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    p.add_argument('--llvm', type=Path, required=True)
    p.add_argument('--build-dir', type=Path, required=True)
    a = p.parse_args()
    image = a.firmware.read_bytes()

    def link(stem, text, data):
        b = a.build_dir / (stem + '-daemon-offline')
        ld, elf, binary = b.with_suffix('.ld'), b.with_suffix('.elf'), b.with_suffix('.bin')
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

    probe = link('host-daemon-relay', host.TEXT, DATA)
    controls = {x: link('host-test-' + x, host.CONTROL, host.CONTROL_DATA) for x in ('arm', 'remove', 'stop')}
    cases = []

    def passed(name):
        cases.append({'case': name, 'passed': True})

    m = DaemonMachine(image, probe, controls)
    m.arm_and_activate()

    # 1. S4CTL:PING initializes sem/uv_async/pthread and replies S4CTL:PONG (phase=4, uv=1, hal=1, tx=0)
    m.deliver_pb('com.meta.s4nativeprobe', 'S4CTL:PING')
    assert m.sem_inited and m.async_cb != 0 and m.worker_fn != 0
    assert m.sent_wear[-1] == ('com.meta.s4nativeprobe', 'S4CTL:PONG:4:1:1:0')
    passed('ping_inits_loop_primitives_and_replies_pong')

    # 2. RF SELECT -> sem_post -> relay_async_cb -> S4Q1 -> pb_recv_hook S4R1 -> NFA 0x10c
    m.hal_frame(bytes([0, 0, len(TUNION_SELECT)]) + TUNION_SELECT)
    assert m.sem_posts == 1 and m.ipc()[4] == 1
    m.run_async_cb()
    ep = m.ipc()[7]
    assert m.sent_wear[-1] == ('com.meta.s4nativeprobe', f'S4Q1:1:{ep}:{TUNION_SELECT.hex()}')
    assert m.ipc()[4] == 2
    m.deliver_pb('com.meta.s4nativeprobe', f'S4R1:1:{ep}:{LONG_FCI_RAPDU}')
    assert m.ipc()[4] == 0 and len(m.enqueued) == 1
    m.call(0x181334, m.enqueued.pop(0))
    assert m.queue[-1]['payload_hex'] == LONG_FCI_RAPDU
    passed('direct_spp_rf_select_roundtrip_without_quickapp')

    # 3. Second APDU in same activation
    m.hal_frame(bytes([0, 0, len(READ_BINARY)]) + READ_BINARY)
    assert m.sem_posts == 2
    m.run_async_cb()
    assert m.sent_wear[-1] == ('com.meta.s4nativeprobe', f'S4Q1:2:{ep}:{READ_BINARY.hex()}')
    m.deliver_pb('com.meta.s4nativeprobe', f'S4R1:2:{ep}:{CARD_INFO_RAPDU}')
    m.call(0x181334, m.enqueued.pop(0))
    assert m.queue[-1]['payload_hex'] == CARD_INFO_RAPDU
    passed('multi_apdu_same_activation_direct_spp')

    # 4. Read-only SELFTEST loopback without RF transmission
    m.deliver_pb('com.meta.s4nativeprobe', 'S4CTL:SELFTEST')
    assert m.sem_posts == 3
    m.run_async_cb()
    assert m.sent_wear[-1] == ('com.meta.s4nativeprobe', 'S4Q1:999901:9999:00a4040008a00000063201010500')
    m.deliver_pb('com.meta.s4nativeprobe', f'S4R1:999901:9999:{LONG_FCI_RAPDU}')
    assert not m.enqueued
    assert m.sent_wear[-1] == ('com.meta.s4nativeprobe', f'S4CTL:SELFTEST_OK:{len(bytes.fromhex(LONG_FCI_RAPDU))}:{0x9000}:4:1')
    passed('selftest_loopback_exercises_full_path_without_rf')

    # 5. Other apps (e.g. Dayweave) pass straight through to original 0x2c480b21
    m.deliver_pb('org.dayweave.mobile', '{"hello":1}')
    assert m.orig_recv_calls == 1
    passed('other_quickapp_messages_pass_through_to_original_handler')

    # 6. Back-to-back 61 05 + 00 00 SELECT before nfc_task updates DM+0x4c to 5 still captures & replies
    m.hal_frame(bytes.fromhex('6106020000'))
    m.cpu.mem_write(DM + 0x4C, b'\x01')  # nfc_task still in DISCOVERY (1) when nfc_readerTask gets 61 05 + SELECT!
    m.hal_frame(ACTIVATION)
    m.hal_frame(bytes([0, 0, len(TUNION_SELECT)]) + TUNION_SELECT)
    assert m.ipc()[4] == 1
    m.run_async_cb()
    ep_race = m.ipc()[7]
    seq_race = m.ipc()[6]
    assert m.sent_wear[-1] == ('com.meta.s4nativeprobe', f'S4Q1:{seq_race}:{ep_race}:{TUNION_SELECT.hex()}')
    # By the time phone replies (~80ms later), nfc_task has processed 61 05 -> DM+0x4c == 5
    m.cpu.mem_write(DM + 0x4C, b'\x05')
    m.deliver_pb('com.meta.s4nativeprobe', f'S4R1:{seq_race}:{ep_race}:{LONG_FCI_RAPDU}')
    assert m.ipc()[4] == 0 and len(m.enqueued) == 1
    m.call(0x181334, m.enqueued.pop(0))
    assert m.queue[-1]['payload_hex'] == LONG_FCI_RAPDU
    passed('back_to_back_activation_and_select_before_dm_state_5_succeeds')

    # 7. S4CTL:DIAG reports route_power_state, activations, last_act_packed (0x020480), and capdu/tx counts
    m.deliver_pb('com.meta.s4nativeprobe', 'S4CTL:DIAG')
    assert m.sent_wear[-1] == ('com.meta.s4nativeprobe', f'S4CTL:DIAG:0:2:{0x020480}:{(3 << 16) | 3}')
    passed('diag_reports_route_power_activations_and_counters')

    # 8. Late reply after deactivation is rejected
    m.hal_frame(bytes([0, 0, len(READ_BINARY)]) + READ_BINARY)
    m.run_async_cb()
    stale_seq, stale_ep = m.ipc()[6], m.ipc()[7]
    m.hal_frame(bytes.fromhex('6106020000'))
    m.deliver_pb('com.meta.s4nativeprobe', f'S4R1:{stale_seq}:{stale_ep}:9000')
    assert not m.enqueued
    passed('deactivation_rejects_late_direct_spp_reply')

    # 9. S4CTL:STOP restores original pb_recv_cb and closes uv_async
    m.deliver_pb('com.meta.s4nativeprobe', 'S4CTL:STOP')
    assert m.sub_cb() == ORIG_PB_RECV
    assert m.async_closed_cb != 0
    assert m.sent_wear[-1][1].startswith('S4CTL:STOPPED:')
    passed('stop_restores_original_subscriber_and_closes_async')

    print(json.dumps({
        'execution': 'OFFLINE_COMPILED_HOST_DAEMON_DIRECT_SPP',
        'cases_passed': len(cases),
        'cases': cases,
        'real_RF_SPP_roundtrip': False,
    }, indent=2))


if __name__ == '__main__':
    main()
