/*
 * Custom Pi 4 (BCM2711) armstub for joe-ng — based verbatim on the official
 * Raspberry Pi armstub8.S (tools/armstubs, BSD-3-Clause; copyright notice below),
 * built for BCM2711 + GIC. Two deliberate differences from stock:
 *
 *   1. setup_gic's IGROUPR loop writes IGROUPR0 (INTIDs 0-31 = the banked SGIs/PPIs)
 *      as well as the SPI banks, so EVERY interrupt — including PPI 30, the
 *      non-secure EL1 physical timer (CNTP_EL0) — is placed in group 1 before the
 *      drop to non-secure EL2. The stock firmware stub only group-1's the SPIs, so
 *      PPI 30 stays secure/group-0 and is undeliverable to our non-secure kernel.
 *      (Verified on real HW: pristine IGROUPR0 read back 0.)
 *   2. The primary core also enables the distributor (GICD_CTLR = 3) instead of
 *      skipping it — we fully replace the firmware stub, so we can't rely on an
 *      earlier stage having done it.
 *
 * Everything else (entry ABI, stub_magic 0x5afe570b at 0xf0, the spin table at
 * 0xd8, kernel_entry32/dtb_ptr32 slots) is kept identical so current firmware
 * loads and hands off exactly as with the stock stub. Build: see armstub/Makefile.
 *
 * Copyright (c) 2016-2019 Raspberry Pi (Trading) Ltd.
 * Copyright (c) 2016 Stephen Warren <swarren@wwwdotorg.org>
 * SPDX-License-Identifier: BSD-3-Clause
 */

#define BCM2711 1
#define GIC 1

#define BIT(x) (1 << (x))

#if BCM2711
#define LOCAL_CONTROL		0xff800000
#define LOCAL_PRESCALER		0xff800008
#else
#define LOCAL_CONTROL		0x40000000
#define LOCAL_PRESCALER		0x40000008
#endif

#define GIC_DISTB		0xff841000
#define GIC_CPUB		0xff842000

#if BCM2711
#define OSC_FREQ		54000000
#else
#define OSC_FREQ		19200000
#endif

#define SCR_RW			BIT(10)
#define SCR_HCE			BIT(8)
#define SCR_SMD			BIT(7)
#define SCR_RES1_5		BIT(5)
#define SCR_RES1_4		BIT(4)
#define SCR_NS			BIT(0)
#define SCR_VAL \
    (SCR_RW | SCR_HCE | SCR_SMD | SCR_RES1_5 | SCR_RES1_4 | SCR_NS)

#define ACTLR_VAL \
	(BIT(0) | BIT(1) | BIT(4) | BIT(5) | BIT(6))

#define CPUECTLR_EL1		S3_1_C15_C2_1
#define CPUECTLR_EL1_SMPEN	BIT(6)

#define SPSR_EL3_D		BIT(9)
#define SPSR_EL3_A		BIT(8)
#define SPSR_EL3_I		BIT(7)
#define SPSR_EL3_F		BIT(6)
#define SPSR_EL3_MODE_EL2H	9
#define SPSR_EL3_VAL \
    (SPSR_EL3_D | SPSR_EL3_A | SPSR_EL3_I | SPSR_EL3_F | SPSR_EL3_MODE_EL2H)

#define L2CTLR_EL1		S3_1_C11_C0_2

#define GICC_CTRLR	0x0
#define GICC_PMR	0x4
#define IT_NR		0x8	// Number of interrupt enable registers (256 total irqs)
#define GICD_CTRLR	0x0
#define GICD_IGROUPR	0x80

.globl _start
_start:
#ifdef LOOPTEST
	/*
	 * Diagnostic build (-DLOOPTEST): hang here immediately, before touching any
	 * hardware. If this stub is actually loaded the core spins forever at 0x0 and
	 * the board stays dark (no banner, no kernel). If the banner still prints, the
	 * firmware ignored armstub= and ran its built-in stub instead.
	 */
	b	.
#endif
#ifdef EL3TEST
	/*
	 * Diagnostic build (-DEL3TEST): hang ONLY if we entered at EL3 (secure). The
	 * group-1 IGROUPR write in setup_gic only takes effect from EL3/secure; if the
	 * firmware hands the stub off at non-secure EL2 instead, that write is RAZ/WI
	 * and PPI 30 stays group-0 (matching igrp0=0). CurrentEL[3:2] = current EL, so
	 * EL3 reads as 0xc. Board dark => EL3 (good); banner prints => NOT EL3 (the bug).
	 */
	mrs	x0, CurrentEL
	and	x0, x0, #0xc
	cmp	x0, #0xc
	b.eq	.
#endif
	/*
	 * LOCAL_CONTROL:
	 * Bit 9 clear: Increment by 1 (vs. 2).
	 * Bit 8 clear: Timer source is 19.2MHz crystal (vs. APB).
	 */
	ldr x0, =LOCAL_CONTROL
	str wzr, [x0]
	/* LOCAL_PRESCALER; divide-by (0x80000000 / register_val) == 1 */
	mov w1, 0x80000000
	str w1, [x0, #(LOCAL_PRESCALER - LOCAL_CONTROL)]

	/* Set L2 read/write cache latency to 3 */
	mrs x0, L2CTLR_EL1
	mov x1, #0x22
	orr x0, x0, x1
	msr L2CTLR_EL1, x0

	/* Set up CNTFRQ_EL0 */
	ldr x0, =OSC_FREQ
	msr CNTFRQ_EL0, x0

	/* Set up CNTVOFF_EL2 */
	msr CNTVOFF_EL2, xzr

	/* Enable FP/SIMD */
	/* Bit 10 (TFP) is set to 0 */
	msr CPTR_EL3, xzr

	/* Set up SCR */
	mov x0, #SCR_VAL
	msr SCR_EL3, x0

	/* Set up ACTLR */
	mov x0, #ACTLR_VAL
	msr ACTLR_EL3, x0

	/* Set SMPEN */
	mov x0, #CPUECTLR_EL1_SMPEN
	msr CPUECTLR_EL1, x0

#ifdef GIC
        bl      setup_gic
#endif
	/*
	 * Set up SCTLR_EL2
	 * All set bits below are res1. LE, no WXN/I/SA/C/A/M
	 */
	ldr x0, =0x30c50830
	msr SCTLR_EL2, x0

	/* Switch to EL2 */
	mov x0, #SPSR_EL3_VAL
	msr spsr_el3, x0
	adr x0, in_el2
	msr elr_el3, x0
	eret
in_el2:

	mrs x6, MPIDR_EL1
	and x6, x6, #0x3
	cbz x6, primary_cpu

	adr x5, spin_cpu0
secondary_spin:
	wfe
	ldr x4, [x5, x6, lsl #3]
	cbz x4, secondary_spin
	mov x0, #0
	b boot_kernel

primary_cpu:
	ldr w4, kernel_entry32
	ldr w0, dtb_ptr32

boot_kernel:
	mov x1, #0
	mov x2, #0
	mov x3, #0
	br x4

.ltorg

.org 0xd8
.globl spin_cpu0
spin_cpu0:
	.quad 0
.org 0xe0
.globl spin_cpu1
spin_cpu1:
	.quad 0
.org 0xe8
.globl spin_cpu2
spin_cpu2:
	.quad 0
.org 0xf0
.globl spin_cpu3
spin_cpu3:
	# Shared with next two symbols/.word
	# FW clears the next 8 bytes after reading the initial value, leaving
	# the location suitable for use as spin_cpu3
.org 0xf0
.globl stub_magic
stub_magic:
	.word 0x5afe570b
.org 0xf4
.globl stub_version
stub_version:
	.word 0
.org 0xf8
.globl dtb_ptr32
dtb_ptr32:
	.word 0x0
.org 0xfc
.globl kernel_entry32
kernel_entry32:
	.word 0x0

.org 0x100

#ifdef GIC

setup_gic:				// Called from secure mode - set ALL interrupts (incl. PPIs) to group 1 and enable.
#ifdef GICPROBE
	ldr	x8, =GIC_DISTB
	ldr	w8, [x8, #GICD_IGROUPR]	// w8 = pristine IGROUPR0 as seen at EL3 (before we write it)
#endif
	ldr	x2, =GIC_DISTB
	mov	w0, #3			// Enable group 0 and 1 IRQs from distributor (every core; shared reg)
	str	w0, [x2, #GICD_CTRLR]
	add	x1, x2, #(GIC_CPUB - GIC_DISTB)
	mov	w0, #0x1e7
	str	w0, [x1, #GICC_CTRLR]	// Enable group 1 IRQs from CPU interface
	mov	w0, #0xff
	str	w0, [x1, #GICC_PMR]	// priority mask
	add	x2, x2, #GICD_IGROUPR
	mov	x0, #(IT_NR * 4)
	mov	w1, #~0			// group 1 all the things — INCLUDING IGROUPR0 (SGIs+PPIs, so PPI 30)
3:
	subs	x0, x0, #4
	str	w1, [x2, x0]
	b.ne	3b
#ifdef GICPROBE
	/*
	 * Read IGROUPR0 back HERE, at EL3, right after writing it, and stash the truth
	 * into scratch RAM (0x00700000: above the image, below the mailbox @8MiB) for
	 * the kernel to print. This bypasses the non-secure IGROUPR read (which may be
	 * RAZ). x2 still = GICD_IGROUPR base, so [x2] is IGROUPR0.
	 */
	ldr	w3, [x2]		// post-write IGROUPR0 @EL3 (expect 0xffffffff if the secure write sticks)
	ldr	x4, =0x00700000
	movz	w6, #0xe1e1
	movk	w6, #0xe1e1, lsl #16	// marker 0xe1e1e1e1 => this probe actually ran
	str	w6, [x4]
	str	w8, [x4, #4]		// pristine IGROUPR0 @EL3 (what the firmware/earlier code left)
	str	w3, [x4, #8]		// post-write IGROUPR0 @EL3
	mrs	x5, CurrentEL
	str	w5, [x4, #12]		// CurrentEL at probe time (0xc = EL3)
#endif
	ret

#endif

.globl dtb_space
dtb_space:
