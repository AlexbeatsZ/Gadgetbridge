// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 only. Calls ONLY the already occupied listener rejection branch.
// Does not enqueue an NFC message, stop discovery, change routing, or open SE.
// Results live in this module's own data segment for read-only Lua xd.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;

// Clang may lower zero-initialized stack arrays to this EABI helper even for
// freestanding code. Supply it locally so the module imports nothing.
void __aeabi_memclr4(void *memory, u32 count) {
    volatile u8 *p = (volatile u8 *)memory;
    while (count--) *p++ = 0;
}

__attribute__((section(".data.result"),used))
volatile u32 s4_stock_ce_result[12] = {0x53444345, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

static void callback(u8 event, void *data) {
    const u8 *p = (const u8 *)data;
    s4_stock_ce_result[3]++;
    s4_stock_ce_result[4] = event;
    s4_stock_ce_result[5] = p[0];
    s4_stock_ce_result[6] = p[2] | ((u32)p[3] << 8);
}

__attribute__((constructor,used)) int module_initialize(void) {
    static const u32 expected[6] = {0x2c340ead, 0x2c341eed, 0x2c340ead,
                                   0x2c340ead, 0x2c3418f9, 0x2c341e69};
    const volatile u32 *table = (const volatile u32 *)0x2c8b4578;
    const volatile u32 *ce_flags = (const volatile u32 *)0x3c2d46d4;
    const volatile u32 *vendor_handler = (const volatile u32 *)0x3c2d46f8;
    const volatile u32 *sys_reg = (const volatile u32 *)0x3c203dac;
    u8 message[48] __attribute__((aligned(4))) = {0};
    u8 (*dispatch)(void *) = (u8 (*)(void *))0x2c340eb5;
    s4_stock_ce_result[1] = 1;  // guard entered, not success
    for (u32 i = 0; i < 6; ++i) if (table[i] != expected[i]) {
        s4_stock_ce_result[1] = 10; return 0;
    }
    if (*(const volatile u32 *)0x2c340eac != 0x2001b580 ||
        *(const volatile u32 *)0x2c340eb0 != 0xbd80af00 ||
        *sys_reg != 0x2c8b4590) {
        s4_stock_ce_result[1] = 11; return 0;
    }
    s4_stock_ce_result[2] = *ce_flags;
    // This exact state guarantees the original handler branches to failure
    // before ANY write to nfa_ce_cb or call to the controller/discovery layer.
    if (*ce_flags != 0x8101 || *vendor_handler != 0) {
        s4_stock_ce_result[1] = 12; return 0;
    }
    message[0] = 1; message[1] = 4;  // event 0x401: REG_LISTEN
    *(u32 *)(message + 8) = (u32)callback;
    message[12] = 1;                // ISO-DEP host (never type 4 eSE)
    const u8 aid[] = {0xf0, 1, 2, 3, 4, 5, 6};
    for (u32 i = 0; i < sizeof(aid); ++i) message[24+i] = aid[i];
    message[40] = sizeof(aid);
    s4_stock_ce_result[7] = dispatch(message);
    s4_stock_ce_result[8] = *ce_flags;
    // The other two entries have independently verified identical stub code.
    message[0] = 2;
    s4_stock_ce_result[9] = dispatch(message);
    message[0] = 3;
    s4_stock_ce_result[10] = dispatch(message);
    s4_stock_ce_result[11] = *ce_flags;
    s4_stock_ce_result[1] = (s4_stock_ce_result[3] == 1 &&
        s4_stock_ce_result[4] == 0x15 && s4_stock_ce_result[5] == 3 &&
        s4_stock_ce_result[6] == 0xffff && s4_stock_ce_result[8] == 0x8101 &&
        s4_stock_ce_result[11] == 0x8101) ? 2 : 13;
    return 0;
}

__attribute__((destructor,used)) static void module_uninitialize(void) { }
