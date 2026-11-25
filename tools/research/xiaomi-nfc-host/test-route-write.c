// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79, exact captured baseline only. No arbitrary command/APDU API.
// Lua must verify current table, own STOP and idle, then readback and restore.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define W(a) (*(const volatile u32 *)(a))
#define H(a) (*(const volatile u16 *)(a))
#define B(a) (*(const volatile u8 *)(a))
#ifndef RESTORE_ORIGINAL
#define RESTORE_ORIGINAL 0
#endif
static const u8 append_command[]={0x21,1,0x17,0,3,
    2,9,0,1,0xf0,1,2,3,4,5,6,
    0,3,0xc0,0xe3,0,0,3,0xc0,0xe3,2};
static const u8 restore_command[]={0x21,1,0x0c,0,2,
    0,3,0xc0,0xe3,0,0,3,0xc0,0xe3,2};
__attribute__((section(".data.result"),used))
volatile u32 s4_test_route_result[16]={0x54414944};
static void response(u8 event,u16 length,const u8 *packet) {
    s4_test_route_result[8]++;
    s4_test_route_result[9]=event;
    s4_test_route_result[10]=length;
    if (event==0x41 && length==4 && packet && packet[0]==0x41 && packet[1]==1 && packet[2]==1) {
        s4_test_route_result[11]=packet[3];
        s4_test_route_result[12]=0x00010141|((u32)packet[3]<<24);
        s4_test_route_result[1]=3;
    } else s4_test_route_result[1]=20;
}
__attribute__((constructor,used)) int module_initialize(void) {
    s4_test_route_result[1]=1;
    s4_test_route_result[2]=RESTORE_ORIGINAL;
    s4_test_route_result[3]=W(0x3c2d46d4);
    s4_test_route_result[4]=W(0x3c2d4c4c);
    s4_test_route_result[5]=H(0x3c2d4c50);
    if (W(0x2c8b459c+26*4)!=0x2c343181 || W(0x2c343180)!=0xb580220c ||
        W(0x2c34e560)!=0x4c0eb5b0 || W(0x2c34e384)!=0x47f0e92d ||
        W(0x2c342e84)!=0x47f0e92d || W(0x2c34de2c)!=0xb082b580) {
        s4_test_route_result[1]=10;return 0;
    }
    if (s4_test_route_result[3]!=0x8101 || !(s4_test_route_result[4]&0x800) ||
        s4_test_route_result[5]<21 || H(0x3c2d4746) || B(0x3c2d4748) ||
        W(0x3c2d4ccc) || B(0x3c2d4c48) || H(0x3c2d4cd8) ||
        B(0x3c2d4d5b)!=1 || W(0x3c2d45cc)!=0x2c34cd85) {
        s4_test_route_result[1]=11;return 0;
    }
    const u8 *cmd=RESTORE_ORIGINAL?restore_command:append_command;
    const u32 size=RESTORE_ORIGINAL?sizeof(restore_command):sizeof(append_command);
    u8 *message=((u8 *(*)(u16))0x2c342e85)(20+size);
    if (!message){s4_test_route_result[1]=12;return 0;}
    for(u32 i=0;i<20+size;++i)message[i]=0;
    *(u16 *)message=0x11a;
    *(u32 *)(message+8)=(u32)response;
    message[13]=size;
    *(u32 *)(message+16)=(u32)(message+20);
    for(u32 i=0;i<size;++i)message[20+i]=cmd[i];
    s4_test_route_result[1]=2;
    ((void (*)(void *))0x2c34de2d)(message);
    return 0;
}
