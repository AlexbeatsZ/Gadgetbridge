#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE fixed Host/SPP mailbox, compiled callbacks and original RF send.

Filesystem, HAL, sleep and task queues are explicit emulation boundaries.
No real SPP, controller, card or timing claim.
"""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import verify_host_session as host
from verify_rf_data import BASE, DM, NFC, SELECT

host.TEXT=0x3D006000
DATA=host.DATA
REQUEST='/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-request.txt'
RESPONSE='/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-response.txt'
ACTIVATION=bytes.fromhex('61050b0102048001000000000000')

class Machine(host.HostMachine):
    def __init__(self,image,probe,controls):
        super().__init__(image,probe,controls);self.files={};self.fds={};self.fd=0
        self.file_calls=[];self.invalidate_on_read=False
    def ipc(self):return list(struct.unpack('<16I',bytes(self.cpu.mem_read(DATA+296,64))))
    def string(self,p):
        raw=bytes(self.cpu.mem_read(p,160));return raw.split(b'\0',1)[0].decode()
    def _trace(self,cpu,a,size,data):
        x,y,z=[cpu.reg_read(r) for r in self.regs[:3]]
        if a==0x2C55D838:
            path=self.string(x);assert path in (REQUEST,RESPONSE)
            self.file_calls.append(('open',path,y));assert y in (1,0x26)
            if y==1 and path not in self.files:self._return(0xffffffff);return
            if y==0x26:self.files[path]=b''
            self.fds[self.fd]=path;self._return(self.fd);self.fd+=1
        elif a==0x2C55D61A:
            assert self.fds[x]==REQUEST
            value=bytes(cpu.mem_read(y,z));self.files[REQUEST]=value
            body=json.loads(value);assert body['apdu']==SELECT.hex() and body['nonce']=='s4-spp-rf-20261125'
            self.file_calls.append(('write',len(value)));self._return(z)
        elif a==0x2C55D5E0:
            assert self.fds[x]==RESPONSE and z==64
            value=self.files[RESPONSE][:z];cpu.mem_write(y,value)
            if self.invalidate_on_read:self.write32(DATA+296+5*4,self.ipc()[5]+1)
            self.file_calls.append(('read',len(value)));self._return(len(value))
        elif a==0x2C55AF94:
            del self.fds[x];self._return(0)
        elif a==0x2C20DC10:
            assert x==20000;self._return(0)
        else:super()._trace(cpu,a,size,data)
    def active(self):
        self.armed();self.cpu.mem_write(DM+0x4C,b'\x05');self.cpu.mem_write(DM+0x52,b'\xff\xff')
        self.hal_frame(ACTIVATION)
    def request(self):self.hal_frame(bytes([0,0,len(SELECT)])+SELECT)
    def pump(self):self.call(self.ipc()[2]-BASE-1)
    def reply(self,seq=None,epoch=None,rapdu='5334544553549000'):
        ipc=self.ipc();self.files[RESPONSE]=f'S4RF1:{ipc[6] if seq is None else seq}:{ipc[7] if epoch is None else epoch}:{rapdu}'.encode()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path)
    p.add_argument('--llvm',type=Path,required=True);p.add_argument('--build-dir',type=Path,required=True)
    p.add_argument('--output',type=Path);a=p.parse_args();image=a.firmware.read_bytes()
    def link(stem,text,data):
        b=a.build_dir/(stem+'-spp-offline');ld=b.with_suffix('.ld');elf=b.with_suffix('.elf');binary=b.with_suffix('.bin')
        ld.write_text(f'SECTIONS {{ . = {text:#x}; .text : {{ *(.text .text.*) *(.rodata*) }}\n'
                      f'. = {data:#x}; .data : {{ *(.data.result) *(.data .data.*) }} .bss : {{ *(.bss*) }}\n'
                      '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
        subprocess.run([str(a.llvm/'ld.lld'),'-T',str(ld),'--entry=module_initialize',str(a.build_dir/(stem+'.o')),'-o',str(elf)],check=True)
        subprocess.run([str(a.llvm/'llvm-objcopy'),'-O','binary','--only-section=.text','--only-section=.data',str(elf),str(binary)],check=True)
        return binary.read_bytes()
    probe=link('host-spp-session',host.TEXT,DATA)
    controls={x:link('host-test-'+x,host.CONTROL,host.CONTROL_DATA) for x in ('arm','remove','stop')}
    pump=link('host-spp-pump',host.CONTROL,host.CONTROL_DATA)
    cases=[]
    def passed(n):cases.append({'case':n,'passed':True})
    m=Machine(image,probe,controls);m.active();m.request()
    assert m.ipc()[4]==1 and m.ipc()[6]==1 and not m.file_calls and not m.enqueued and not m.queue
    passed('hal_publishes_fixed_request_without_files_sleep_or_local_response')
    m.pump();assert m.ipc()[4]==2 and m.ipc()[8]==1 and not m.fds
    request=json.loads(m.files[REQUEST]);assert request['seq']==1 and request['epoch']==m.ipc()[7]
    passed('outside_nfc_worker_writes_complete_fixed_request_to_own_private_path')
    for name,seq,epoch,rapdu in [('wrong_seq',2,None,'5334544553549000'),('old_epoch',None,65535,'5334544553549000'),('wrong_response',None,None,'0000000000009000')]:
        m.reply(seq,epoch,rapdu);m.pump();assert not m.enqueued and m.ipc()[4]==2
        passed('response_refuses_'+name)
    m.reply();m.pump();assert m.ipc()[9:11]==[1,1] and len(m.enqueued)==1
    m.call(0x181334,m.enqueued.pop(0));assert m.queue[-1]['payload_hex']=='5334544553549000'
    passed('matched_phone_reply_submits_original_nfa_send_and_no_module_pointer')
    for name,change in [('deactivation',lambda m:m.hal_frame(bytes.fromhex('6106020000'))),
                        ('original_eSE_handle',lambda m:m.cpu.mem_write(DM+0x52,bytes(2))),
                        ('epoch_changed_during_read',lambda m:setattr(m,'invalidate_on_read',True))]:
        m=Machine(image,probe,controls);m.active();m.request();m.pump();m.reply();change(m);m.pump()
        assert not m.enqueued and not m.queue and not m.fds
        passed('late_reply_refused_after_'+name)
    m=Machine(image,probe,controls);m.active();m.request();m.pump()
    for _ in range(41):m.pump()
    assert m.ipc()[4]==0 and m.ipc()[14]==1 and not m.enqueued
    passed('bounded_worker_tick_timeout_clears_pending_without_local_fallback')
    m=Machine(image,probe,controls);m.active();m.request()
    m.cpu.mem_write(host.CONTROL,pump);m.cpu.ctl_remove_cache(host.CONTROL,host.CONTROL+0x1000)
    m.call(host.CONTROL-BASE);assert m.read32(host.CONTROL_DATA+4)==10
    passed('worker_refuses_unbound_resident_address_before_dereference')
    m.write32(host.CONTROL_DATA+64,DATA);m.call(host.CONTROL-BASE,instruction_bound=100000)
    assert m.read32(host.CONTROL_DATA+4)==2 and m.read32(host.CONTROL_DATA+8)==50 and m.ipc()[3]==0
    passed('bound_synchronous_worker_returns_after_50_pumps_without_persistent_task')
    result={'execution':'OFFLINE_COMPILED_HOST_SPP_AND_ORIGINAL_SEND','cases_passed':len(cases),'cases':cases,
            'boundaries':['HAL forwarding','filesystem','sleep','NFA queue','original RF send queue'],
            'real_RF_SPP_roundtrip':False,'not_verified':['live worker/quickapp concurrency','real total latency','WTX','cross-activation race after original send enqueue']}
    if a.output:a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
