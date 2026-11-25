#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""OFFLINE compiled private-file probe, with explicit synthetic filesystem/sleep."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
from verify_rf_data import BASE, OfflineMachine

TEXT, DATA = 0x3D008000, 0x3D009000
QUICK = '/data/quickapp/files/com.meta.s4nativeprobe/quickapp-ipc-test.txt'
NATIVE = '/data/quickapp/files/com.meta.s4nativeprobe/native-ipc-test.txt'

class Machine(OfflineMachine):
    def __init__(self,image,probe,*,seed=b'S4IPC-QUICKAPP-1125',partial=False):
        super().__init__(image);self.cpu.mem_write(TEXT,probe)
        self.files={} if seed is None else {QUICK:seed}
        self.fds={};self.next_fd=0;self.calls=[];self.partial=partial
    def string(self,p):
        out=b''
        for i in range(160):
            c=bytes(self.cpu.mem_read(p+i,1))
            if c==b'\0':return out.decode()
            out+=c
        raise AssertionError('unbounded path')
    def _trace(self,cpu,a,size,data):
        r0,r1,r2,*_=self.regs
        x,y,z=[cpu.reg_read(r) for r in (r0,r1,r2)]
        if a==0x2C55D838:
            path=self.string(x);assert path in (QUICK,NATIVE)
            self.calls.append(('open',path,y,z))
            assert y in (1,0x26) and z==0o600
            if y==1 and path not in self.files:self._return(0xffffffff);return
            if y==0x26:self.files[path]=b''
            fd=self.next_fd;self.next_fd+=1;self.fds[fd]=path;self._return(fd)
        elif a==0x2C55D5E0:
            assert x in self.fds and z==32
            value=self.files[self.fds[x]][:z];cpu.mem_write(y,value)
            self.calls.append(('read',x,len(value)));self._return(len(value))
        elif a==0x2C55D61A:
            assert self.fds.get(x)==NATIVE
            value=bytes(cpu.mem_read(y,z));assert value==b'S4IPC-NATIVE-1125'
            if self.partial:value=value[:-1]
            self.files[NATIVE]=value;self.calls.append(('write',x,len(value)));self._return(len(value))
        elif a==0x2C55AF94:
            assert x in self.fds;del self.fds[x]
            self.calls.append(('close',x));self._return(0)
        elif a==0x2C20DC10:
            assert x==10000;self.calls.append(('usleep',x));self._return(0)
        else:super()._trace(cpu,a,size,data)
    def header(self):return list(struct.unpack('<16I',bytes(self.cpu.mem_read(DATA,64))))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path)
    p.add_argument('--llvm',type=Path,required=True);p.add_argument('--build-dir',type=Path,required=True)
    p.add_argument('--output',type=Path);a=p.parse_args();stem=a.build_dir/'native-ipc-test-offline'
    ld=stem.with_suffix('.ld');elf=stem.with_suffix('.elf');binary=stem.with_suffix('.bin')
    ld.write_text(f'SECTIONS {{ . = {TEXT:#x}; .text : {{ *(.text .text.*) *(.rodata*) }}\n'
                  f'. = {DATA:#x}; .data : {{ *(.data.result) *(.data*) }}\n'
                  '/DISCARD/ : { *(.ARM.exidx*) *(.init_array*) } }\n')
    subprocess.run([str(a.llvm/'ld.lld'),'-T',str(ld),'--entry=module_initialize',str(a.build_dir/'native-ipc-test.o'),'-o',str(elf)],check=True)
    subprocess.run([str(a.llvm/'llvm-objcopy'),'-O','binary','--only-section=.text','--only-section=.data',str(elf),str(binary)],check=True)
    image=a.firmware.read_bytes();probe=binary.read_bytes();cases=[]
    for name,opts,error,phase in [('success_including_valid_fd_zero',{},0,5),
                                ('missing_seed',{'seed':None},1,1),
                                ('wrong_seed',{'seed':b'OTHER-SYNTHETIC-TEXT'},2,1),
                                ('partial_write',{'partial':True},4,3),
                                ('firmware_mismatch',{},0,10)]:
        m=Machine(image,probe,**opts)
        if name=='firmware_mismatch':m.cpu.mem_write(0x2C55D838,bytes(4))
        m.call(TEXT-BASE);h=m.header()
        assert h[15]==error and h[1]==phase and not m.fds,(name,h)
        if phase==10:assert not m.calls
        cases.append({'case':name,'passed':True,'calls':m.calls})
    result={'execution':'OFFLINE_COMPILED_PRIVATE_FILE_PROBE','cases_passed':len(cases),'cases':cases,
            'boundaries':['POSIX filesystem wrappers','usleep'], 'real_native_quickapp_ipc':False}
    if a.output:a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
