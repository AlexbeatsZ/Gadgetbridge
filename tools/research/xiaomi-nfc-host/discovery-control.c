// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 only. Enqueue original NFA discovery action; no callback/hooks.
// No NCI/route/APDU writes in this module. The original stack owns discovery.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define W(a) (*(const volatile u32 *)(a))
#define H(a) (*(const volatile u16 *)(a))
#define B(a) (*(const volatile u8 *)(a))
#ifndef DISCOVERY_START
#define DISCOVERY_START 0
#endif
__attribute__((section(".data.result"),used))
volatile u32 s4_discovery_result[16] = {0x4443544c};

__attribute__((constructor,used)) int module_initialize(void) {
    const u16 event = DISCOVERY_START ? 0x10e : 0x10f;
    s4_discovery_result[1] = 1;
    s4_discovery_result[2] = event;
    s4_discovery_result[3] = H(0x3c2d4746);
    s4_discovery_result[4] = B(0x3c2d4748);
    s4_discovery_result[5] = W(0x3c2d46d4);
    if (W(0x2c8b459c+14*4) != 0x2c3434b1 ||
        W(0x2c8b459c+15*4) != 0x2c342079 ||
        W(0x2c342078) != 0xb0acb580 ||
        W(0x2c3434b0) != 0xb0acb580 ||
        W(0x2c342e84) != 0x47f0e92d ||
        W(0x2c34de2c) != 0xb082b580) {
        s4_discovery_result[1] = 10; return 0;
    }
    if (s4_discovery_result[5] != 0x8101 ||
        W(0x3c2d4ccc) || B(0x3c2d4c48) ||
        H(0x3c2d4cd8) || B(0x3c2d4d5b) != 1 ||
        s4_discovery_result[4] != (DISCOVERY_START ? 0 : 1) ||
        s4_discovery_result[3] != (DISCOVERY_START ? 0 : 1)) {
        s4_discovery_result[1] = 11; return 0;
    }
    u8 *message = ((u8 *(*)(u16))0x2c342e85)(8);
    if (!message) { s4_discovery_result[1] = 12; return 0; }
    for (u32 i=0; i<8; ++i) message[i]=0;
    *(u16 *)message=event;
    ((void (*)(void *))0x2c34de2d)(message);
    // Enqueued is not a successful stop/start. Read original DM state later.
    s4_discovery_result[1] = 2;
    return 0;
}
