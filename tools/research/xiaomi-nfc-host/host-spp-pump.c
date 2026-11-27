// SPDX-License-Identifier: AGPL-3.0-or-later
// Synchronous NSH worker for one second, outside all NFC tasks/callbacks.
// Resident address binding is patched only from this experiment's lsmod.
typedef unsigned int u32;
#define W(a) (*(const volatile u32 *)(a))
__attribute__((section(".data.result"),used)) volatile u32 s4_pump_control[16]={0x53505043};
__attribute__((section(".data.binding"),used)) volatile u32 s4_pump_address=0xdeadc0de;
__attribute__((constructor,used)) int module_initialize(void) {
    const u32 target=s4_pump_address;
    s4_pump_control[1]=1;
    if(target<0x3c300000 || target>0x3e000000 || (target&3)) {s4_pump_control[1]=10;return 0;}
    const u32 pump=W(target+304);
    if(W(target)!=0x53483454 || W(target+296)!=0x53505049 || W(target+300)!=1 ||
       pump<0x3c300000 || pump>=0x3e000000 || !(pump&1) ||
       W(0x2c20dc10)!=0xb084b580){s4_pump_control[1]=10;return 0;}
    for(u32 i=0;i<50 && W(target+4)==4;++i) {
        ((void (*)(void))pump)();
        ((int (*)(u32))0x2c20dc11)(20000);
        s4_pump_control[2]++;
    }
    s4_pump_control[1]=2;return 0;
}
