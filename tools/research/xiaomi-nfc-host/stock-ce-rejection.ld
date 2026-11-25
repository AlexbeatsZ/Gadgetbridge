ENTRY(module_initialize)
SECTIONS {
 .text : { *(.text .text.*) }
 .rodata : { *(.rodata .rodata.*) }
 .data : { KEEP(*(.data.result)) *(.data .data.*) }
 .bss : { *(.bss .bss.* COMMON) }
 .init_array : { KEEP(*(.init_array)) KEEP(*(.init_array.*)) }
 .fini_array : { KEEP(*(.fini_array)) KEEP(*(.fini_array.*)) }
}
