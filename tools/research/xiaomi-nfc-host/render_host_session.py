#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Render a bounded watch research resource; does not install or execute it."""
import argparse
from pathlib import Path
import re

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--build-dir',type=Path,required=True)
p.add_argument('--run-id',required=True)
p.add_argument('--window-seconds',type=int,default=0)
p.add_argument('--collect',action='store_true')
p.add_argument('--spp',action='store_true',help='fixed synthetic phone backend; no local RF answer')
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if not re.fullmatch(r'host-[a-z0-9-]{1,32}',a.run_id):p.error('scoped run-id required')
if not 0<=a.window_seconds<=300:p.error('bounded window 0..300 required')
root=Path(__file__).resolve().parent
canonical=(root/'test-route-roundtrip.lua.in').read_text()
common=canonical[canonical.index('local function command('):canonical.index('command("modules-before.txt","lsmod")')]
template='host-session-collect.lua.in' if a.collect else 'host-session-setup.lua.in'
s=(root/template).read_text().replace('@COMMON_HELPERS@',common).replace('@RUN_ID@',a.run_id).replace('@WINDOW@',str(a.window_seconds))
stems=['get-routing-capture','get-routing-status','discovery-stop','discovery-start',
       'test-route-append','test-route-restore','host-test-session']
payloads={stem:(a.build_dir/(('host-spp-session' if a.spp and stem=='host-test-session' else stem)+'.elf')).read_bytes() for stem in stems}
table=','.join('{"'+stem+'","'+data.hex()+'"}' for stem,data in payloads.items())
s=s.replace('@PAYLOADS@',table)
if a.spp and not a.collect:
    s=s.replace('local payloads=',
        'for _,name in ipairs({"rf-spp-request.txt","rf-spp-response.txt"})do\n'
        ' local f=io.open(base.."/"..name,"w");if f then f:write("");f:close()end\n'
        'end\nlocal payloads=')
    pump=(a.build_dir/'host-spp-pump.elf').read_bytes().hex()
    if pump.count('dec0adde')!=1:p.error('SPP pump needs exactly one binding')
    s=s.replace('bind("host-test-arm-bound",', 'bind("host-spp-pump-bound","'+pump+'",tonumber(addr)) and\n     bind("host-test-arm-bound",')
    # One synchronous, bounded worker module per slice; NSH owns the calls.
    # No background task or callback survives each insmod/rmmod pair.
    s=s.replace('if '+str(a.window_seconds)+' > 0 then script[#script+1]="sleep '+str(a.window_seconds)+'"end',
        'for i=1,'+str(a.window_seconds)+' do\n'
        '    script[#script+1]="insmod "..base.."/host-spp-pump-bound.elf gb_spp_pump"\n'
        '    script[#script+1]="rmmod gb_spp_pump"\n'
        '   end')
tags={'CAPTURE':'get-routing-capture','BARRIER':'get-routing-status',
      'ARM':'host-test-arm','REMOVE':'host-test-remove','STOP_OWNED':'host-test-stop'}
for tag,stem in tags.items():
    data=(a.build_dir/(stem+'.elf')).read_bytes()
    if stem.startswith('host-test-') and stem!='host-test-session' and data.count(bytes.fromhex('dec0adde'))!=1:
        p.error('control module must have exactly one invalid-default binding')
    s=s.replace('@'+tag+'_HEX@',data.hex())
if re.search(r'@[A-Z_]+@',s):p.error('unresolved resource placeholder')
a.output.write_text(s)
print(f'Rendered {len(s)} bytes; execute=False; collect={a.collect}; window={a.window_seconds}')
