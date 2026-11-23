// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 only. Capture GET_ROUTING notifications, forward every frame.
// No route writes, RF/APDU logging, SE session, file descriptor or polling loop.
// Unload only after self-detach AND a subsequent original-HAL query completes.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define WORD(a) (*(const volatile u32 *)(a))
#define BYTE(a) (*(const volatile u8 *)(a))
#define HALF(a) (*(const volatile u16 *)(a))
#define NFC 0x3c2d4b8c
#define HAL_SLOT (*(volatile u32 *)0x3c2d45cc)
#define ORIGINAL_HAL 0x2c34cd85
#define PACKET_LIMIT 4
#define BYTE_LIMIT 1040

// All fields are u32, followed by raw routing notifications only.
// [state, ese_before, hal_before, hal_after, rsp_count, ntf_count, active,
// complete, error, byte_count, wrapper, last_status, forwarded, other_frames]
__attribute__((section(".data.result"),used))
volatile struct {
    u32 header[32];
    u32 lengths[PACKET_LIMIT];
    u8 packets[BYTE_LIMIT];
} s4_route_capture = {{0x52544350}, {0}, {0}};

static void detach(u32 reason) {
    if (HAL_SLOT == s4_route_capture.header[11]) {
        HAL_SLOT = ORIGINAL_HAL;
        __asm__ volatile("dmb" ::: "memory");
        s4_route_capture.header[4] = HAL_SLOT;
        s4_route_capture.header[1] = reason;
    } else {
        s4_route_capture.header[9] = 6;  // Ownership changed: do not overwrite.
    }
}

static void receive(u16 length, u8 *packet) {
    s4_route_capture.header[7]++;
    s4_route_capture.header[13]++;
    if (packet && length >= 2 && packet[0] == 0x41 && packet[1] == 2) {
        s4_route_capture.header[5]++;
        if (length != 4 || packet[2] != 1) {
            s4_route_capture.header[9] = 1;
            detach(5);
        } else {
            s4_route_capture.header[12] = packet[3];
            // A second immutable query bounds capture if no final NTF arrives.
            // This is self-detach in the HAL callback owner, never Lua poking
            // a pointer while an unobserved callback might have loaded it.
            if (packet[3] || s4_route_capture.header[5] >= 2) detach(4);
        }
    } else if (packet && length >= 2 && packet[0] == 0x61 && packet[1] == 2) {
        u32 count = s4_route_capture.header[6];
        u32 used = s4_route_capture.header[10];
        if (count < PACKET_LIMIT && length <= 260 && used + length <= BYTE_LIMIT) {
            s4_route_capture.lengths[count] = length;
            for (u32 i = 0; i < length; ++i) s4_route_capture.packets[used+i] = packet[i];
            s4_route_capture.header[10] = used + length;
        } else {
            s4_route_capture.header[9] = 4;
        }
        if (s4_route_capture.header[5] != 1 || s4_route_capture.header[12]) {
            s4_route_capture.header[9] = 2;  // Uncorrelated notification.
        } else if (length < 5 || length != (u32)packet[2] + 3 || packet[3] > 1) {
            s4_route_capture.header[9] = 3;
        }
        s4_route_capture.header[6] = count + 1;
        if (length >= 5 && packet[3] == 0) {
            if (!s4_route_capture.header[9]) s4_route_capture.header[8] = 1;
            detach(3);
        } else if (s4_route_capture.header[9]) {
            detach(5);
        }
    } else {
        s4_route_capture.header[14]++;  // Count only; never store other packets.
    }
    ((void (*)(u16, u8 *))ORIGINAL_HAL)(length, packet);
    s4_route_capture.header[7]--;
}

__attribute__((constructor,used)) int module_initialize(void) {
    s4_route_capture.header[1] = 1;
    s4_route_capture.header[2] = WORD(0x3c2d46d4);
    s4_route_capture.header[3] = HAL_SLOT;
    s4_route_capture.header[11] = (u32)receive;
    s4_route_capture.header[15] = sizeof(s4_route_capture);
    s4_route_capture.header[16] = WORD(0x3c2d3c20);
    s4_route_capture.header[17] = WORD(NFC+0x1d4);
    s4_route_capture.header[18] = BYTE(0x3c2d4748);
    if (WORD(0x2c8b459c + 26*4) != 0x2c343181 ||
        WORD(0x2c343180) != 0xb580220c ||
        WORD(0x2c34e384) != 0x47f0e92d ||
        WORD(0x2c340d34) != 0x4b03b580 ||
        WORD(0x2c34cd84) != 0x4b12b5f8 ||
        WORD(0x2c342e84) != 0x47f0e92d ||
        WORD(0x2c34de2c) != 0xb082b580) {
        s4_route_capture.header[1] = 10; return 0;
    }
    if (s4_route_capture.header[2] != 0x8101 || HAL_SLOT != ORIGINAL_HAL ||
        s4_route_capture.header[16] != 0x2c340d35 ||
        s4_route_capture.header[17] != 0x3c2d3738 ||
        WORD(NFC+0x140) || BYTE(NFC+0xbc) || BYTE(NFC+0x1cf) != 1 ||
        HALF(NFC+0x14c) || s4_route_capture.header[18] > 1) {
        s4_route_capture.header[1] = 11; return 0;
    }
    u8 *message = ((u8 *(*)(u16))0x2c342e85)(24);
    if (!message) { s4_route_capture.header[1] = 12; return 0; }
    for (u32 i = 0; i < 24; ++i) message[i] = 0;
    *(u16 *)message = 0x11a;
    message[13] = 3;
    *(u32 *)(message+16) = (u32)(message+20);
    message[20] = 0x21; message[21] = 2;
    s4_route_capture.header[1] = 2;
    HAL_SLOT = (u32)receive;
    __asm__ volatile("dmb" ::: "memory");
    ((void (*)(void *))0x2c34de2d)(message);
    return 0;
}
