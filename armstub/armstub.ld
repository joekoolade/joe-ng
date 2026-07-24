/* The armstub is loaded and entered by the firmware at physical address 0x0. */
ENTRY(_start)
SECTIONS
{
    . = 0x0;
    .text : { *(.text*) *(.rodata*) *(.data*) }
    /DISCARD/ : { *(.note*) *(.comment) *(.ARM.attributes) }
}
