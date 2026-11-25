// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79: original DM add/delete on spare slot, all within NFA callback.
// Discovery must be stopped. No route writes, RF handling, SE or APDU command.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define W(a) (*(const volatile u32 *)(a))
#define H(a) (*(const volatile u16 *)(a))
#define B(a) (*(const volatile u8 *)(a))
#define DM 0x3c2d46fc
#define ENTRIES (DM+0x58)
__attribute__((section(".data.result"),used)) volatile struct {
    u32 h[16];u8 before[40],registered[40],after[40];
} s4_discovery_slot={{0x52464453},{0},{0},{0}};
static void snapshot(volatile u8 *out) {
    for(u32 i=0;i<40;++i)out[i]=B(ENTRIES+i);
}
static void listener(u8 event,void *data) {
    (void)data;s4_discovery_slot.h[5]++;s4_discovery_slot.h[6]=event;
}
static int idle(void) {
    return H(DM+0x4a)==0 && B(DM+0x4c)==0 && W(0x3c2d46d4)==0x8101 &&
           B(ENTRIES)==1 && B(ENTRIES+20)==0;
}
static void response(u8 event,u16 length,const u8 *packet) {
    s4_discovery_slot.h[7]=event;s4_discovery_slot.h[8]=length;
    if(event!=0x42 || length!=4 || !packet || packet[0]!=0x41 || packet[1]!=2 || packet[2]!=1 || packet[3] || !idle()) {
        s4_discovery_slot.h[1]=20;return;
    }
    s4_discovery_slot.h[9]=0x00010241;
    snapshot(s4_discovery_slot.before);
    s4_discovery_slot.h[4]=((u16 (*)(u32,u8,void *))0x2c344755)(0x40000,0,listener);
    snapshot(s4_discovery_slot.registered);
    if(s4_discovery_slot.h[4]!=1) {s4_discovery_slot.h[1]=21;return;}
    ((void (*)(u16))0x2c341311)(1);
    // Delete clears in_use only. Restore all originally unused-slot bytes so
    // no module pointer remains, rather than unloading behind a stale pointer.
    for(u32 i=20;i<40;++i)*(volatile u8 *)(ENTRIES+i)=s4_discovery_slot.before[i];
    __asm__ volatile("dmb" ::: "memory");
    snapshot(s4_discovery_slot.after);
    u32 same=1;
    for(u32 i=0;i<40;++i)if(s4_discovery_slot.before[i]!=s4_discovery_slot.after[i])same=0;
    s4_discovery_slot.h[12]=same;
    s4_discovery_slot.h[14]=W(0x3c2d4b90);
    s4_discovery_slot.h[1]=3;
}
__attribute__((constructor,used)) int module_initialize(void) {
    s4_discovery_slot.h[1]=1;s4_discovery_slot.h[2]=W(0x3c2d46d4);
    s4_discovery_slot.h[3]=B(DM+0x4c);s4_discovery_slot.h[13]=W(0x3c2d4b90);
    if(W(0x2c344754)!=0x4b0db5f0 || W(0x2c341310)!=0x2801b580 ||
       W(0x2c8b459c+26*4)!=0x2c343181 || W(0x2c34e560)!=0x4c0eb5b0 ||
       W(0x2c343180)!=0xb580220c || W(0x2c342e84)!=0x47f0e92d || W(0x2c34de2c)!=0xb082b580) {
        s4_discovery_slot.h[1]=10;return 0;
    }
    if(!idle() || W(0x3c2d4ccc) || B(0x3c2d4c48) || H(0x3c2d4cd8) || B(0x3c2d4d5b)!=1) {
        s4_discovery_slot.h[1]=11;return 0;
    }
    u8 *message=((u8 *(*)(u16))0x2c342e85)(24);
    if(!message){s4_discovery_slot.h[1]=12;return 0;}
    for(u32 i=0;i<24;++i)message[i]=0;
    *(u16 *)message=0x11a;*(u32 *)(message+8)=(u32)response;
    message[13]=3;*(u32 *)(message+16)=(u32)(message+20);message[20]=0x21;message[21]=2;
    s4_discovery_slot.h[1]=2;((void (*)(void *))0x2c34de2d)(message);return 0;
}
