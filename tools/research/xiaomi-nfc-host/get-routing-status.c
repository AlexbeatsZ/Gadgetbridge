// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 diagnostic: ONLY immutable NCI RF_GET_LISTEN_MODE_ROUTING.
// No route writes, SE session, arbitrary input, card APDU, or callback patch.
// An asynchronous callback requires the Lua owner to prove it has returned
// and the original stack has cleared the slot BEFORE unloading this module.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define WORD(address) (*(const volatile u32 *)(address))
#define BYTE(address) (*(const volatile u8 *)(address))
#define HALF(address) (*(const volatile u16 *)(address))
#define NFC 0x3c2d4b8c

__attribute__((section(".data.result"),used))
volatile u32 s4_route_result[16] = {0x5254534e};

static void response(u8 event, u16 length, const u8 *packet) {
    s4_route_result[8]++;
    s4_route_result[9] = event;
    s4_route_result[10] = length;
    // Record only the four-byte GET_ROUTING status packet, never other data.
    if (event == 0x42 && length == 4 && packet && packet[0] == 0x41 &&
        packet[1] == 2 && packet[2] == 1) {
        s4_route_result[11] = packet[3];
        s4_route_result[12] = 0x00010241 | ((u32)packet[3] << 24);
        s4_route_result[1] = 3;  // status response, NOT a routing-table backup
    } else {
        s4_route_result[1] = 20;
    }
}

__attribute__((constructor,used)) int module_initialize(void) {
    s4_route_result[1] = 1;
    s4_route_result[2] = WORD(0x3c2d46d4);
    s4_route_result[3] = WORD(NFC + 0x140);
    s4_route_result[4] = BYTE(NFC + 0xbc);
    s4_route_result[5] = BYTE(NFC + 0x1cf);
    s4_route_result[6] = HALF(NFC + 0x14c);
    s4_route_result[7] = BYTE(0x3c2d4748);
    s4_route_result[13] = (u32)response;
    if (WORD(0x2c8b459c + 26*4) != 0x2c343181 ||
        WORD(0x2c343180) != 0xb580220c ||
        WORD(0x2c34e560) != 0x4c0eb5b0 ||
        WORD(0x2c34e384) != 0x47f0e92d ||
        WORD(0x2c342e84) != 0x47f0e92d ||
        WORD(0x2c34de2c) != 0xb082b580) {
        s4_route_result[1] = 10; return 0;
    }
    if (s4_route_result[2] != 0x8101 || s4_route_result[3] ||
        s4_route_result[4] || s4_route_result[5] != 1 ||
        s4_route_result[6] || s4_route_result[7] > 1) {
        s4_route_result[1] = 11; return 0;
    }
    u8 *(*allocate)(u16) = (u8 *(*)(u16))0x2c342e85;
    void (*send)(void *) = (void (*)(void *))0x2c34de2d;
    u8 *message = allocate(24);
    if (!message) { s4_route_result[1] = 12; return 0; }
    for (u32 i = 0; i < 24; ++i) message[i] = 0;
    *(u16 *)(message) = 0x11a;
    *(u32 *)(message + 8) = (u32)response;
    message[13] = 3;
    *(u32 *)(message + 16) = (u32)(message + 20);
    message[20] = 0x21; message[21] = 0x02; message[22] = 0x00;
    s4_route_result[1] = 2;  // queued, not a controller response
    send(message);
    return 0;
}
