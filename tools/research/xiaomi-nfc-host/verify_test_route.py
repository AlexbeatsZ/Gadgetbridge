#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE constant test-route writers; never contacts an NFC controller."""
import argparse
import json
from pathlib import Path
import struct
import subprocess

from verify_rf_data import BASE, CE, DM, NFC
from verify_nci_routing import NciMachine, RAW_RESPONSE, RESPONSE_MESSAGE
from routing_table import plan

TEXT, DATA = 0x3D008000, 0x3D009000


class RouteMachine(NciMachine):
    def __init__(self,image,probe,**kwargs):
        super().__init__(image,**kwargs)
        self.cpu.mem_write(TEXT,probe)
        self.cpu.mem_write(CE+0xAC,struct.pack('<I',0x8101))
        self.ce_before=bytes(self.cpu.mem_read(CE,0xD4))
        self.write32(NFC+0xC0,0x63E3A)
        self.cpu.mem_write(NFC+0xC4,struct.pack('<H',1030))
        self.write32(0x3C2D45CC,0x2C34CD85)
        self.enqueued=[]

    def _trace(self,cpu,address,size,data):
        if address==BASE+0x18DE2C:
            self.enqueued.append(cpu.reg_read(self.regs[0]));self._return()
        else:super()._trace(cpu,address,size,data)

    def result(self):return struct.unpack('<16I',bytes(self.cpu.mem_read(DATA,64)))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware',type=Path);p.add_argument('--llvm',type=Path,required=True)
    p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--output',type=Path)
    a=p.parse_args();image=a.firmware.read_bytes();probes={};cases=[]
    for action in ('append','restore'):
        stem=a.build_dir/('test-route-'+action+'-offline')
        ld=stem.with_suffix('.ld');elf=stem.with_suffix('.elf');binary=stem.with_suffix('.bin')
        ld.write_text(f'SECTIONS {{ . = {TEXT:#x}; .text : {{ *(.text .text.*) *(.rodata*) }}\n'
                      f'. = {DATA:#x}; .data : {{ *(.data.result) *(.data .data.*) }}\n'
                      '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
        subprocess.run([str(a.llvm/'ld.lld'),'-T',str(ld),'--entry=module_initialize',
                        str(a.build_dir/('test-route-'+action+'.o')),'-o',str(elf)],check=True)
        subprocess.run([str(a.llvm/'llvm-objcopy'),'-O','binary','--only-section=.text',
                        '--only-section=.data',str(elf),str(binary)],check=True)
        probes[action]=binary.read_bytes()
    expected=plan([bytes.fromhex('61020c00020003c0e3000003c0e302')])
    def passed(name):cases.append({'case':name,'passed':True})
    for action,key in [('append','install_command_hex'),('restore','restore_command_hex')]:
        m=RouteMachine(image,probes[action]);m.call(TEXT-BASE)
        assert m.result()[1]==2 and len(m.enqueued)==1
        assert m.call(0x181334,m.enqueued[0])==0
        assert m.commands==[expected[key]] and m.freed==m.enqueued
        callback=m.read32(NFC+0x140)
        assert TEXT<=callback<DATA and bytes(m.cpu.mem_read(NFC+0xBC,1))==b'\x01'
        packet=bytes.fromhex('41010100')
        m.cpu.mem_write(RESPONSE_MESSAGE,struct.pack('<4H',0x4000,4,1,0))
        m.cpu.mem_write(RESPONSE_MESSAGE+9,packet)
        m.call(RAW_RESPONSE,RESPONSE_MESSAGE)
        assert m.result()[1]==3 and m.result()[8:13]==(1,0x41,4,0,0x00010141)
        assert m.read32(NFC+0x140)==0 and bytes(m.cpu.mem_read(NFC+0xBC,1))==b'\0'
        passed('compiled_'+action+'_constant_matches_lossless_plan_and_callback_clears')
    for name,addr,value in [('no_aid_routing',NFC+0xC0,struct.pack('<I',0x6363A)),
                           ('capacity_too_small',NFC+0xC4,struct.pack('<H',20)),
                           ('discovery_running',DM+0x4A,struct.pack('<H',1)),
                           ('rf_active',DM+0x4C,b'\x05'),
                           ('raw_callback_busy',NFC+0x140,struct.pack('<I',0x12345679)),
                           ('command_busy',NFC+0xBC,b'\x01'),
                           ('queue_busy',NFC+0x14C,b'\x01\x00'),
                           ('window_closed',NFC+0x1CF,b'\x00'),
                           ('unexpected_hal_owner',0x3C2D45CC,struct.pack('<I',TEXT+1))]:
        m=RouteMachine(image,probes['append']);m.cpu.mem_write(addr,value);m.call(TEXT-BASE)
        assert m.result()[1]==11 and not m.enqueued and not m.commands
        passed(name+'_refuses_write')
    m=RouteMachine(image,probes['append'],fail_allocation=True);m.call(TEXT-BASE)
    assert m.result()[1]==12 and not m.enqueued
    passed('allocation_failure_no_write')
    result={'execution':'OFFLINE_COMPILED_CONSTANT_WRITERS_AND_ORIGINAL_NCI_QUEUE',
            'cases_passed':len(cases),'cases':cases,'real_route_write':False,
            'boundaries':['NFA queue','GKI allocation/free','HAL output','timers'],
            'not_verified':['controller acceptance','actual readback and restoration',
                            'RF SELECT and APDU','SPP']}
    if a.output:a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
