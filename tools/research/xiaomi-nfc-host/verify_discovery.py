#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE discovery probes + original DM actions; RF state transitions mocked.

Only the actual action's local flags and queued event ABI are established here.
Controller completion, timers, discovery configuration and RF are not emulated.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess

from verify_rf_data import BASE, CE, DM, NFC, MESSAGE
from verify_nci_routing import NciMachine

TEXT, DATA = 0x3D008000, 0x3D009000


class DiscoveryMachine(NciMachine):
    def __init__(self, image, probe, *, start=False, fail_allocation=False):
        super().__init__(image, fail_allocation=fail_allocation)
        self.cpu.mem_write(TEXT, probe)
        self.cpu.mem_write(CE+0xAC, struct.pack('<I', 0x8101))
        self.ce_before = bytes(self.cpu.mem_read(CE, 0xD4))
        self.cpu.mem_write(DM+0x4A, struct.pack('<HB', 0 if start else 1, 0 if start else 1))
        self.enqueued, self.notifications, self.deactivations, self.starts = [], [], [], []

    def _trace(self, cpu, address, size, data):
        r0, r1, *_ = self.regs
        if address == BASE+0x18DE2C:
            self.enqueued.append(cpu.reg_read(r0))
            self._return()
        elif address == 0x2C340E9C:
            pointer=cpu.reg_read(r1)
            self.notifications.append([cpu.reg_read(r0), bytes(cpu.mem_read(pointer,1))[0]])
            self._return()
        elif address == 0x2C712DE8:
            self.deactivations.append(cpu.reg_read(r0)); self._return(0)
        elif address == 0x2C3431E4:
            self.starts.append(True); self._return()
        else:
            super()._trace(cpu,address,size,data)

    def result(self):
        return struct.unpack('<16I',bytes(self.cpu.mem_read(DATA,64)))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware',type=Path)
    p.add_argument('--llvm',type=Path,required=True)
    p.add_argument('--build-dir',type=Path,required=True)
    p.add_argument('--output',type=Path)
    a=p.parse_args(); image=a.firmware.read_bytes()
    for offset,word in ((0x6F45D4,0x2C3434B1),(0x6F45D8,0x2C342079),
                        (0x182078,0xB0ACB580),(0x1834B0,0xB0ACB580)):
        if struct.unpack_from('<I',image,offset)[0]!=word:raise ValueError('discovery ABI mismatch')
    probes={}
    for action in ('stop','start'):
        stem=a.build_dir/('discovery-'+action+'-offline')
        ld=stem.with_suffix('.ld'); elf=stem.with_suffix('.elf'); binary=stem.with_suffix('.bin')
        ld.write_text(f'SECTIONS {{ . = {TEXT:#x}; .text : {{ *(.text .text.*) }}\n'
                      f'. = {DATA:#x}; .data : {{ *(.data.result) *(.data .data.*) }}\n'
                      '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
        subprocess.run([str(a.llvm/'ld.lld'),'-T',str(ld),'--entry=module_initialize',
                        str(a.build_dir/('discovery-'+action+'.o')),'-o',str(elf)],check=True)
        subprocess.run([str(a.llvm/'llvm-objcopy'),'-O','binary','--only-section=.text',
                        '--only-section=.data',str(elf),str(binary)],check=True)
        probes[action]=binary.read_bytes()
    cases=[]
    def passed(name):cases.append({'case':name,'passed':True})
    for action,event,flags,state in [('stop',0x10F,2,1),('start',0x10E,0x11,0)]:
        m=DiscoveryMachine(image,probes[action],start=action=='start')
        m.call(TEXT-BASE)
        assert m.result()[1:3]==(2,event) and len(m.enqueued)==1
        message=m.enqueued[0]
        assert bytes(m.cpu.mem_read(message,8))==struct.pack('<4H',event,0,0,0)
        assert m.call(0x181334,message)==1
        assert struct.unpack('<H',bytes(m.cpu.mem_read(DM+0x4A,2)))[0]==flags
        assert bytes(m.cpu.mem_read(DM+0x4C,1))==bytes([state])
        assert m.deactivations==([0] if action=='stop' else [])
        assert len(m.starts)==(action=='start')
        assert not m.commands and not m.notifications
        passed('compiled_'+action+'_enqueues_original_action_without_module_callback')
    for name,addr,value in [('rf_active',DM+0x4C,b'\x05'),
                           ('already_disabled',DM+0x4A,b'\x00\x00'),
                           ('raw_command_busy',NFC+0xBC,b'\x01'),
                           ('window_closed',NFC+0x1CF,b'\x00'),
                           ('queue_busy',NFC+0x14C,b'\x01\x00')]:
        m=DiscoveryMachine(image,probes['stop']);m.cpu.mem_write(addr,value);m.call(TEXT-BASE)
        assert m.result()[1]==11 and not m.enqueued
        passed(name+'_refuses_stop')
    m=DiscoveryMachine(image,probes['stop'],fail_allocation=True);m.call(TEXT-BASE)
    assert m.result()[1]==12 and not m.enqueued
    passed('allocation_failure_enqueues_nothing')
    m=DiscoveryMachine(image,probes['start'],start=True)
    m.cpu.mem_write(DM+0x4C,b'\x01');m.call(TEXT-BASE)
    assert m.result()[1]==11 and not m.enqueued
    passed('resume_does_not_guess_incomplete_stop_state')
    # Original action semantics: notify on already enabled/disabled; active
    # state without enabled is rejected by START instead of silently restarting.
    for name,action,flags,state,event,status in [
        ('start_already_enabled',0x1834B0,1,1,0x1E,0),
        ('start_wrong_state',0x1834B0,0,1,0x1E,6),
        ('stop_already_disabled',0x182078,0,0,0x1F,0),
    ]:
        m=DiscoveryMachine(image,probes['stop'])
        m.cpu.mem_write(DM+0x4A,struct.pack('<HB',flags,state));m.call(action,MESSAGE)
        assert m.notifications==[[event,status]] and not m.deactivations and not m.starts
        passed('original_'+name)
    result={'execution':'OFFLINE_COMPILED_PROBES_AND_ORIGINAL_DM_ACTIONS_WITH_SYNTHETIC_RAM',
            'cases_passed':len(cases),'cases':cases,'real_discovery_stop_start':False,
            'boundaries':['GKI allocation','NFA message queue','application notification',
                          'RF deactivation completion','discovery start/configuration'],
            'not_verified':['live stop completion','live start/configuration completion',
                            'unchanged controller routing after discovery restart','external RF']}
    if a.output:a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
