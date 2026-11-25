#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE spare-slot add/delete probe and original activation dispatch.

Synthetic registration callbacks and RAM. No live route, RF or SE operations.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess

from verify_rf_data import BASE, CE, DM, NFC, EVENT_DATA
from verify_nci_routing import NciMachine, RAW_RESPONSE, RESPONSE_MESSAGE

TEXT, DATA = 0x3D008000, 0x3D009000
OLD, HOST = 0x3D000300, 0x3D000280
REAL_ENTRY = bytes.fromhex('01000000e90e342c0000c0000000340000003400')


class SlotMachine(NciMachine):
    def __init__(self,image,probe,**kwargs):
        super().__init__(image,**kwargs)
        self.cpu.mem_write(TEXT,probe)
        self.cpu.mem_write(CE+0xAC,struct.pack('<I',0x8101))
        self.ce_before=bytes(self.cpu.mem_read(CE,0xD4))
        self.cpu.mem_write(DM+0x58,REAL_ENTRY+bytes(20))
        self.enqueued,self.disc_events=[],[]
        self.write32(0x3C203DE8,0x3D004000)
        self.cpu.mem_write(0x3D004000+7,b'\xff\xff')
        self.cpu.mem_write(DM+0x11E,b'\xc0\x00\xc0')

    def _trace(self,cpu,address,size,data):
        if address==BASE+0x18DE2C:
            self.enqueued.append(cpu.reg_read(self.regs[0]));self._return()
        elif address in (OLD,HOST):
            self.disc_events.append({'target':'original_eSE' if address==OLD else 'test_host',
                                     'event':cpu.reg_read(self.regs[0])})
            self._return()
        else:super()._trace(cpu,address,size,data)

    def result(self):return struct.unpack('<16I',bytes(self.cpu.mem_read(DATA,64)))

    def complete_query(self):
        self.call(0x181334,self.enqueued[0])
        self.cpu.mem_write(RESPONSE_MESSAGE,struct.pack('<4H',0x4000,4,1,0))
        self.cpu.mem_write(RESPONSE_MESSAGE+9,bytes.fromhex('41020100'))
        self.call(RAW_RESPONSE,RESPONSE_MESSAGE)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware',type=Path);p.add_argument('--llvm',type=Path,required=True)
    p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--output',type=Path)
    a=p.parse_args();image=a.firmware.read_bytes()
    stem=a.build_dir/'discovery-slot-check-offline';ld=stem.with_suffix('.ld')
    elf=stem.with_suffix('.elf');binary=stem.with_suffix('.bin')
    ld.write_text(f'SECTIONS {{ . = {TEXT:#x}; .text : {{ *(.text .text.*) *(.rodata*) }}\n'
                  f'. = {DATA:#x}; .data : {{ *(.data.result) *(.data .data.*) }}\n'
                  '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
    subprocess.run([str(a.llvm/'ld.lld'),'-T',str(ld),'--entry=module_initialize',
                    str(a.build_dir/'discovery-slot-check.o'),'-o',str(elf)],check=True)
    subprocess.run([str(a.llvm/'llvm-objcopy'),'-O','binary','--only-section=.text',
                    '--only-section=.data',str(elf),str(binary)],check=True)
    probe=binary.read_bytes();cases=[]
    def passed(name):cases.append({'case':name,'passed':True})
    m=SlotMachine(image,probe);before=bytes(m.cpu.mem_read(DM+0x58,40))
    m.call(TEXT-BASE);assert m.result()[1]==2
    m.complete_query();r=m.result()
    assert r[1]==3 and r[4:9]==(1,0,0,0x42,4) and r[12]==1
    assert m.commands==['210200'] and m.read32(NFC+0x140)==0
    assert bytes(m.cpu.mem_read(DM+0x58,40))==before
    registered=bytes(m.cpu.mem_read(DATA+104,40))
    assert registered[:20]==REAL_ENTRY and registered[20]==1
    assert int.from_bytes(registered[32:36],'little')==0x40000
    assert registered[30]==0 and TEXT<int.from_bytes(registered[24:28],'little')<DATA
    passed('compiled_probe_registers_spare_host_slot_then_restores_all_original_bytes')
    for name,addr,value in [('discovery_running',DM+0x4A,b'\x01\0'),
                           ('both_slots_used',DM+0x6C,b'\x01'),
                           ('original_slot_absent',DM+0x58,b'\0'),
                           ('raw_queue_busy',NFC+0xBC,b'\x01')]:
        m=SlotMachine(image,probe);m.cpu.mem_write(addr,value);before=bytes(m.cpu.mem_read(DM+0x58,40))
        m.call(TEXT-BASE);assert m.result()[1]==11 and not m.enqueued
        assert bytes(m.cpu.mem_read(DM+0x58,40))==before
        passed(name+'_refuses_register')
    m=SlotMachine(image,probe);m.call(TEXT-BASE)
    m.cpu.mem_write(DM+0x6C,b'\x01');before=bytes(m.cpu.mem_read(DM+0x58,40))
    m.complete_query();assert m.result()[1]==20 and bytes(m.cpu.mem_read(DM+0x58,40))==before
    passed('state_changed_before_nfa_callback_refuses_register')
    # Actual S4 activation dispatch selects host using technology bookkeeping,
    # not an AID lookup. This is evidence for the next integration requirement.
    for name,host_map,interface,target in [
        ('old_technology_map_still_selects_eSE',0xC0,2,'original_eSE'),
        ('temporary_DH_map_selects_test_listener',0,2,'test_host'),
        ('direct_eSE_interface_remains_original',0,0,'original_eSE'),
    ]:
        m=SlotMachine(image,probe)
        m.write32(DM+0x5C,OLD|1)
        assert m.call(0x184754,0x40000,0,HOST|1)==1
        # The discovery-start configuration selects each entry's active mask;
        # its RF/controller work is an explicit emulation boundary here.
        m.write32(DM+0x7C,0x40000)
        m.cpu.mem_write(DM+0x11E,bytes([host_map]))
        event=bytearray(64);event[0]=1;event[1]=4;event[2]=0x80;event[0x27]=interface
        m.cpu.mem_write(EVENT_DATA,bytes(event))
        assert m.call(0x1810A0,EVENT_DATA)==0
        assert m.disc_events==[{'target':target,'event':1}]
        passed('original_activation_'+name)
    result={'execution':'OFFLINE_COMPILED_SPARE_SLOT_PROBE_AND_ORIGINAL_ARM_DISPATCH',
            'cases_passed':len(cases),'cases':cases,'real_host_apdu_callback':False,
            'boundaries':['NFA enqueue','HAL output','GKI/timers','discovery callbacks'],
            'not_verified':['live spare-slot registration/removal','live host activation',
                            'callback lifecycle across real activation/deactivation','RF APDU']}
    if a.output:a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
