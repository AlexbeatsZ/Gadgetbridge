#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE host-session callback/ownership and original S4 activation/send code.

No controller/RF/SPP. The selected discovery mask, RF stop completion, NCI
window/timers and send queue are explicit emulation boundaries.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess

from verify_rf_data import BASE, CE, DM, NFC, EVENT_DATA, MESSAGE, SELECT
from verify_nci_routing import NciMachine, RAW_RESPONSE, RESPONSE_MESSAGE
from verify_discovery_slots import REAL_ENTRY

TEXT, DATA, CONTROL, CONTROL_DATA = 0x3D008000,0x3D009000,0x3D00A000,0x3D00B000
ORIGINAL_DISC, ORIGINAL_RF = 0x2C344699,0x2C34472D
HAL_SLOT, ORIGINAL_HAL = 0x3C2D45CC,0x2C34CD85


class HostMachine(NciMachine):
    def __init__(self,image,probe,controls):
        super().__init__(image)
        self.cpu.mem_write(TEXT,probe);self.controls=controls
        self.cpu.mem_write(CE+0xAC,struct.pack('<I',0x8101))
        self.ce_before=bytes(self.cpu.mem_read(CE,0xD4))
        self.cpu.mem_write(DM+0x58,REAL_ENTRY+bytes(20))
        self.before_entries=bytes(self.cpu.mem_read(DM+0x58,40))
        self.write32(NFC+4,ORIGINAL_RF);self.write32(NFC+0xA4,ORIGINAL_DISC)
        self.write32(HAL_SLOT,ORIGINAL_HAL);self.hal_forwarded=[]
        self.write32(0x3C203DE8,0x3D004000)
        self.cpu.mem_write(0x3D004000+7,b'\xff\xff')
        self.cpu.mem_write(DM+0x11E,b'\xc0\x00\xc0')
        self.enqueued=[];self.se_events=[]
        self.force_activation_sink=False;self.discovery_return=None

    def _trace(self,cpu,address,size,data):
        if address==ORIGINAL_DISC-1 and self.force_activation_sink:
            self.discovery_return=cpu.reg_read(self.regs[4])&~1
        if address==self.discovery_return:
            # Real RF metadata observed the stock sink after the activation
            # callback returned. Model that ownership change explicitly.
            self.write32(NFC+4,ORIGINAL_RF);self.discovery_return=None
        if address==ORIGINAL_HAL-1:
            self.hal_forwarded.append(bytes(cpu.mem_read(cpu.reg_read(self.regs[1]),cpu.reg_read(self.regs[0]))))
            self._return()
        elif address==BASE+0x18DE2C:
            self.enqueued.append(cpu.reg_read(self.regs[0]));self._return()
        elif address==0x2C340EE8:
            self.se_events.append(cpu.reg_read(self.regs[0]));self._return()
        elif address==0x2C3431E4:
            # RF reconfiguration is a boundary; model its original callback
            # replacement to exercise the session's deactivation lifecycle.
            self.write32(NFC+0xA4,ORIGINAL_DISC)
            self.cpu.mem_write(DM+0x4C,b'\x00');self._return()
        else:super()._trace(cpu,address,size,data)

    def header(self):return list(struct.unpack('<32I',bytes(self.cpu.mem_read(DATA,128))))

    def rf_meta(self):return list(struct.unpack('<16I',bytes(self.cpu.mem_read(DATA+168,64))))

    def hal_meta(self):return list(struct.unpack('<16I',bytes(self.cpu.mem_read(DATA+232,64))))

    def hal_frame(self,packet):
        target=self.read32(HAL_SLOT);ptr=0x3D00C000
        self.cpu.mem_write(ptr,packet);self.call(target-BASE-1,len(packet),ptr)

    def finish_query(self):
        message=self.enqueued.pop(0)
        self.call(0x181334,message)
        self.cpu.mem_write(RESPONSE_MESSAGE,struct.pack('<4H',0x4000,4,1,0))
        self.cpu.mem_write(RESPONSE_MESSAGE+9,bytes.fromhex('41020100'))
        if self.read32(HAL_SLOT)!=ORIGINAL_HAL:self.hal_frame(bytes.fromhex('41020100'))
        self.call(RAW_RESPONSE,RESPONSE_MESSAGE)
        assert self.read32(NFC+0x140)==0
        self.cpu.mem_write(NFC+0x1CF,b'\x01') # Emulated window/timer completion.

    def control(self,action):
        self.cpu.mem_write(CONTROL,self.controls[action])
        # Different compiled modules reuse this synthetic address. Invalidate
        # Unicorn's translated code; live modules get independent load slots.
        self.cpu.ctl_remove_cache(CONTROL,CONTROL+0x1000)
        assert self.read32(CONTROL_DATA)==0x4843544C
        self.write32(CONTROL_DATA+64,DATA)
        self.call(CONTROL-BASE)
        assert self.read32(CONTROL_DATA+4)==2
        self.finish_query()

    def registered(self):
        self.call(TEXT-BASE);assert self.header()[1]==2
        self.finish_query();assert self.header()[1]==3

    def armed(self):
        self.registered()
        self.cpu.mem_write(DM+0x4A,struct.pack('<HB',1,1))
        self.write32(DM+0x7C,0x40000) # Selected by emulated discovery configuration.
        self.control('arm');assert self.header()[1]==4

    def activate(self,interface=2):
        event=bytearray(64);event[0]=1;event[1]=4;event[2]=0x80;event[0x27]=interface
        self.cpu.mem_write(EVENT_DATA,bytes(event))
        self.call(self.read32(NFC+0xA4)-BASE-1,0x4004,EVENT_DATA)

    def receive(self,payload=SELECT,conn=0):
        self.packet(payload)
        self.cpu.mem_write(EVENT_DATA,struct.pack('<II',0,MESSAGE))
        self.call(self.read32(NFC+4)-BASE-1,conn,0x6003,EVENT_DATA)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware',type=Path);p.add_argument('--llvm',type=Path,required=True)
    p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--output',type=Path)
    a=p.parse_args();image=a.firmware.read_bytes()
    def link(stem,text,data):
        out=a.build_dir/(stem+'-offline');ld=out.with_suffix('.ld');elf=out.with_suffix('.elf');binary=out.with_suffix('.bin')
        ld.write_text(f'SECTIONS {{ . = {text:#x}; .text : {{ *(.text .text.*) *(.rodata*) }}\n'
                      f'. = {data:#x}; .data : {{ *(.data.result) *(.data .data.*) }}\n'
                      '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
        subprocess.run([str(a.llvm/'ld.lld'),'-T',str(ld),'--entry=module_initialize',str(a.build_dir/(stem+'.o')),'-o',str(elf)],check=True)
        subprocess.run([str(a.llvm/'llvm-objcopy'),'-O','binary','--only-section=.text','--only-section=.data',str(elf),str(binary)],check=True)
        return binary.read_bytes()
    probe=link('host-test-session',TEXT,DATA)
    controls={x:link('host-test-'+x,CONTROL,CONTROL_DATA) for x in ('arm','remove','stop')}
    cases=[]
    def passed(name):cases.append({'case':name,'passed':True})
    m=HostMachine(image,probe,controls);m.registered()
    assert bytes(m.cpu.mem_read(DM+0x58,20))==REAL_ENTRY and m.header()[4]==1
    m.control('remove');assert m.header()[1]==5 and bytes(m.cpu.mem_read(DM+0x58,40))==m.before_entries
    assert m.read32(NFC+0xA4)==ORIGINAL_DISC and m.read32(NFC+4)==ORIGINAL_RF
    passed('registration_and_removal_without_arming_restore_all_original_entry_bytes')
    m=HostMachine(image,probe,controls);m.armed();m.activate()
    assert m.header()[7]==1 and m.read32(NFC+4)==m.header()[16]
    assert bytes(m.cpu.mem_read(DM+0x11E,1))==b'\xc0' and not m.se_events
    assert bytes(m.cpu.mem_read(DM+0x4C,1))==b'\x05'
    passed('original_full_discovery_callback_selects_extra_host_and_restores_technology_map')
    freed_before=len(m.freed);m.receive()
    assert m.header()[9:11]==[1,1] and m.freed[freed_before:]==[MESSAGE]
    assert m.queue[-1]['payload_hex']=='5334544553549000'
    assert m.rf_meta()[:8]==[1,1,0x6003,0,0,13,4,5]
    assert m.header()[27]>0 and m.header()[30]==0x028004
    passed('test_select_submits_fixed_response_through_original_send_path_and_frees_once')
    m.receive(SELECT[:-1]);assert m.header()[9]==2
    passed('test_select_without_le_is_also_exact_match')
    count=len(m.queue);freed_before=len(m.freed);m.receive(bytes.fromhex('00a4040005a00000000000'))
    assert len(m.queue)==count and m.header()[11]==1 and m.freed[freed_before:]==[MESSAGE]
    passed('non_test_aid_forwarded_to_original_sink_without_response_or_payload_log')
    m.call(m.header()[17]-BASE-1,2,EVENT_DATA)
    assert m.read32(NFC+4)==ORIGINAL_RF and m.header()[24]==0
    passed('own_listener_deactivation_restores_original_rf_callback')
    m.cpu.mem_write(DM+0x4A,bytes(3));m.control('remove')
    assert m.header()[1]==5 and m.read32(NFC+0xA4)==ORIGINAL_DISC
    assert bytes(m.cpu.mem_read(DM+0x58,40))==m.before_entries
    assert m.read32(NFC+0x140)==0 and not m.header()[5] and not m.header()[6]
    passed('stop_completion_then_remove_clears_all_module_references')
    m=HostMachine(image,probe,controls);m.armed();m.activate(interface=0)
    assert m.header()[7]==0 and m.se_events==[1]
    assert bytes(m.cpu.mem_read(DM+0x11E,1))==b'\xc0'
    passed('direct_eSE_activation_uses_original_registration_and_unchanged_map')
    m=HostMachine(image,probe,controls);m.armed();m.write32(NFC+4,0x3D000901)
    m.activate();assert m.header()[28]>=1 and m.read32(NFC+4)==0x3D000901
    assert m.rf_meta()[8]==0x3D000901
    passed('unexpected_rf_callback_owner_not_overwritten')
    m=HostMachine(image,probe,controls);m.armed();m.force_activation_sink=True;m.activate()
    assert m.rf_meta()[8]==ORIGINAL_RF and m.rf_meta()[12]==m.header()[16]
    m.receive();assert m.header()[9:11]==[1,1]
    passed('live_observed_activation_tail_sink_reinstall_repaired_before_test_select')
    m=HostMachine(image,probe,controls);m.armed()
    test=bytes([0,0,len(SELECT)])+SELECT
    other=bytes.fromhex('000003a5a5a5')
    m.hal_frame(test);m.hal_frame(other)
    assert m.hal_meta()[3:5]==[2,1] and m.hal_forwarded[-2:]==[test,other]
    assert m.hal_meta()[0]==0 and m.read32(HAL_SLOT)==m.hal_meta()[14]
    m.hal_frame(bytes.fromhex('41060100'))
    assert m.read32(HAL_SLOT)==ORIGINAL_HAL and m.hal_meta()[1]==3 and m.hal_meta()[0]==0
    passed('passive_hal_counts_only_test_match_and_forwards_all_packets_then_self_detaches_on_stop')
    m=HostMachine(image,probe,controls);m.armed();m.hal_frame(bytes.fromhex('41020100'))
    assert m.read32(HAL_SLOT)==ORIGINAL_HAL and m.hal_meta()[1]==2
    passed('passive_hal_get_routing_barrier_detaches_without_any_rf_activation')
    m=HostMachine(image,probe,controls);m.armed();m.activate()
    event=bytearray(16);event[1]=0;event[2]=1
    m.cpu.mem_write(EVENT_DATA,bytes(event))
    m.call(m.read32(NFC+0xA4)-BASE-1,0x4005,EVENT_DATA)
    assert m.header()[8]==1 and m.read32(NFC+4)==ORIGINAL_RF
    assert m.read32(NFC+0xA4)==m.header()[15]
    passed('original_deactivation_and_discovery_restart_keep_session_wrapper_owned')
    for name,handle,allowed in [('owned_test_listen',1,True),('original_eSE_listen',0,False)]:
        m=HostMachine(image,probe,controls);m.armed();m.activate()
        m.cpu.mem_write(DM+0x52,struct.pack('<H',handle))
        m.cpu.mem_write(CONTROL,controls['stop']);m.cpu.ctl_remove_cache(CONTROL,CONTROL+0x1000)
        m.write32(CONTROL_DATA+64,DATA);m.call(CONTROL-BASE)
        assert m.read32(CONTROL_DATA+4)==(2 if allowed else 11)
        if allowed:
            assert len(m.enqueued)==1 and bytes(m.cpu.mem_read(m.enqueued[0],8))==struct.pack('<4H',0x10F,0,0,0)
        else:assert not m.enqueued
        passed('bounded_stop_'+name+('_allowed' if allowed else '_refused'))
    result={'execution':'OFFLINE_COMPILED_HOST_SESSION_AND_ORIGINAL_FULL_DISCOVERY_AND_RF_SEND',
            'cases_passed':len(cases),'cases':cases,'real_host_rf_apdu':False,
            'boundaries':['HAL output','GKI/timers','NFA queue','selected discovery mask',
                          'RF stop completion','RF send queue','original eSE listener'],
            'not_verified':['live activation payload ABI','live callback ownership during RF',
                            'automatic timeout restoration','external reader RAPDU','SPP','WTX']}
    if a.output:a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
