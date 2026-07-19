# Sources

Per the first-principles rule (CLAUDE.md, PLAN.md §1): we read the reference
docs and the *ideas* behind prior VMs, but write every line ourselves. This file
logs what informed each piece so encodings and boot facts are auditable.

## A64 instruction encodings (asm/A64.java)

- **ARM Architecture Reference Manual, ARMv8-A (A-profile), A64 Instruction Set.**
  Chapter C6 (A64 Base Instruction Descriptions) and C4 (encoding index).
  - Hint space (NOP/YIELD/WFE/WFI/SEV/SEVL) — C6.2, "HINT". Base `0xD503201F`,
    (CRm:op2) selects the hint.
  - Unconditional branch (immediate) B/BL — C6.2. `imm26 = offset >> 2`,
    PC-relative, signed, ±128 MiB.
  - Unconditional branch (register) BR/RET — C6.2.
  - Wide moves MOVZ/MOVK/MOVN — C6.2. `hw` selects the 16-bit lane (shift hw*16).
  - System register move MRS/MSR — C5.2/C6.2. Fields (L at [21], o0 at [19],
    op1/CRn/CRm/op2); the (op0,op1,CRn,CRm,op2) tuples for the boot registers are
    from the ARM ARM system-register index. Layout solved and checked against the
    two anchors MPIDR_EL1=`0xD53800A0` and HCR_EL2=`0xD51C1100`.
  - ERET, barriers DSB/DMB/ISB — C6.2 (SY option = 0b1111).
  - Load/store unsigned-offset LDR/STR/LDRB/STRB — C6 (imm12 scaled by size).
  - ADD/SUB immediate; MOV(reg)=ORR alias; MOV SP via ADD — C6.
  - Branches B.cond / CBZ/CBNZ / TBZ/TBNZ — C6.2 (imm19/imm14 = offset>>2).
  - Every constant above is pinned in `test/asm/A64Test.java` (61 checks) and
    re-derived from the field layout, not copied from a disassembler.

## Boot / target facts (config.txt, CodeBuffer.LOAD_ADDRESS)

- **Raspberry Pi 4 boot / config.txt docs** — `arm_64bit=1` required for AArch64;
  raw `kernel8.img` loaded at `0x80000`; firmware enters at EL2.
- **BCM2711 ARM Peripherals** — MMIO base `0xFE000000`. AUX mini-UART (UART1)
  register map at `0xFE215000` and its init sequence (enable, 8-bit LCR, clear
  FIFOs, baud divisor, CNTL tx/rx enable), plus GPIO GPFSEL1 ALT5 for GPIO14/15
  and the BCM2711 `GPIO_PUP_PDN_CNTRL_REG0` pull register — all in
  `board/bcm2711/Bcm2711.java`, used by M1 `vm/EmitBoot.java`. Baud divisor for
  115200 depends on the pinned core clock and needs on-silicon calibration.

## Classfile format + bytecode (classfile/ClassFile.java, compiler/BaselineCompiler.java)

- **The Java Virtual Machine Specification (JVMS), Java SE** — §4 ClassFile
  structure and constant pool, fields and method access flags, §4.7.3 the Code
  attribute, and §6 the instruction set. Opcodes lowered so far: nop/return/
  Xreturn, goto, const pushes, local load/store + iinc, add/sub/and,
  i2l/l2i/i2b/i2c, if/if_icmp branches, invokestatic/invokespecial, new, dup,
  getfield/putfield, aload/astore. The parser and lowering are written from the
  spec; the object layout (`objectmodel`), the calling convention, and the
  `magic.Magic` intrinsic set + A64 lowering are ours.

## Concepts (not code) referenced

- **Jikes RVM** and **JOE / bare-metal JVM** writeups — *ideas only* for the
  boot-image writer, VM magic (unboxed Address/Word), TIB / object model, and
  baseline compiler shape. No code is ported; those ISAs were IA-32/PPC anyway.
