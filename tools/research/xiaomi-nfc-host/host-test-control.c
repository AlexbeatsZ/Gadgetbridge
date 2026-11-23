// SPDX-License-Identifier: AGPL-3.0-or-later
// A one-shot queue message referring to the resident test-session callback.
// Patch SESSION_ADDRESS only to the verified module data address from lsmod.
// This module itself leaves no async callback, pointer or RF registration.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define W(a) (*(const volatile u32 *)(a))
#define H(a) (*(const volatile u16 *)(a))
#define B(a) (*(const volatile u8 *)(a))
#ifndef SESSION_ADDRESS
#define SESSION_ADDRESS 0xDEADC0DE
#endif
#ifndef REMOVE_SESSION
#define REMOVE_SESSION 0
#endif
#ifndef STOP_DISCOVERY
#define STOP_DISCOVERY 0
#endif
__attribute__((section(".data.result"),used)) volatile u32 s4_host_control[16]={0x4843544c};
__attribute__((section(".data.binding"),used)) volatile u32 s4_session_address=SESSION_ADDRESS;
__attribute__((constructor,used)) int module_initialize(void) {
    const u32 target=s4_session_address;
    s4_host_control[1]=1;s4_host_control[2]=target;s4_host_control[3]=REMOVE_SESSION;
    if(target<0x3c300000 || target>0x3e000000 || (target&3) ||
       W(target)!=0x53483454 || W(target+19*4)!=1 ||
       W(0x2c8b459c+26*4)!=0x2c343181 || W(0x2c34de2c)!=0xb082b580) {
        s4_host_control[1]=10;return 0;
    }
    const u32 phase=W(target+4);
#if STOP_DISCOVERY
    const u32 state=B(0x3c2d4748);
    if((phase!=3 && phase!=4) || H(0x3c2d4746)!=1 ||
       (state!=1 && !((state==5 || state==6) && phase==4 && H(0x3c2d474e)==1 &&
                      W(0x3c2d4b90)==W(target+16*4) && W(target+24*4))) ||
       W(0x3c2d46d4)!=0x8101 || W(0x3c2d4ccc) || B(0x3c2d4c48)) {
        s4_host_control[1]=11;return 0;
    }
    u8 *m=((u8 *(*)(u16))0x2c342e85)(8);
    if(!m){s4_host_control[1]=12;return 0;}
    for(u32 i=0;i<8;++i)m[i]=0;
    *(u16 *)m=0x10f;
#else
    const u32 callback=W(target+(REMOVE_SESSION?21:20)*4);
    s4_host_control[4]=callback;
    if((REMOVE_SESSION?(phase!=3 && phase!=4):(phase!=3)) ||
       callback<0x3c300000 || callback>=0x3e000000 || !(callback&1) ||
       W(0x3c2d46d4)!=0x8101 || W(0x3c2d4ccc) || B(0x3c2d4c48) ||
       H(0x3c2d4cd8) || B(0x3c2d4d5b)!=1 ||
       H(0x3c2d4746)!=(REMOVE_SESSION?0:1) || B(0x3c2d4748)!=(REMOVE_SESSION?0:1)) {
        s4_host_control[1]=11;return 0;
    }
    u8 *m=((u8 *(*)(u16))0x2c342e85)(24);
    if(!m){s4_host_control[1]=12;return 0;}
    for(u32 i=0;i<24;++i)m[i]=0;
    *(u16 *)m=0x11a;*(u32 *)(m+8)=callback;m[13]=3;*(u32 *)(m+16)=(u32)(m+20);m[20]=0x21;m[21]=2;
#endif
    s4_host_control[1]=2;((void (*)(void *))0x2c34de2d)(m);return 0;
}
