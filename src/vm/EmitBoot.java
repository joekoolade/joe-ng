package vm;

import asm.A64;
import asm.CodeBuffer;

import java.nio.charset.StandardCharsets;

import static asm.A64.*;
import static board.bcm2711.Bcm2711.*;

/**
 * M1 "first light": emit the boot routine that brings the Pi 4 up far enough to
 * print over the mini-UART (PLAN.md §4). It:
 *   1. reads {@code CurrentEL} and, if at EL2, drops to EL1 via {@code ERET};
 *   2. enables FP/SIMD at EL1 (or Java floats would trap) and sets a stack;
 *   3. initializes the AUX mini-UART (GPIO alt5 + config + enable);
 *   4. writes "hello from joe2\r\n", polling the TX FIFO;
 *   5. parks in a {@code wfe} spin.
 *
 * <p><b>This is the board-bringup half of M1, emitted directly against the
 * assembler.</b> It deliberately isolates the hang-prone EL-drop/MMU-off/UART
 * path from the compiler (UART-first observability, PLAN.md §6). The
 * metacircular half — compiling this same logic from {@code VM.boot}'s Java
 * bytecode via the baseline compiler — is the next step (M1c); when it lands,
 * its output should match these words.
 */
public final class EmitBoot {

    private static final byte[] MESSAGE = "hello from joe2\r\n".getBytes(StandardCharsets.US_ASCII);

    public static CodeBuffer build() {
        CodeBuffer cb = new CodeBuffer();               // links at 0x80000, entry = word 0

        // === _start: are we at EL2? CurrentEL holds the EL in bits[3:2]. ===
        cb.emit(mrs(0, CurrentEL));
        int tbzToEl1 = cb.emit(tbz(0, 3, 0));           // bit3==0 => already EL1, skip drop

        // --- drop EL2 -> EL1 (primary core; PLAN.md §5.1 B) ---
        set64(cb, 0, 0x8000_0000L); cb.emit(msr(HCR_EL2, 0));      // HCR_EL2.RW = 1 (EL1 is AArch64)
        set64(cb, 0, 0x33FFL);      cb.emit(msr(CPTR_EL2, 0));     // don't trap FP/SIMD to EL2
        set64(cb, 0, 0x3L);         cb.emit(msr(CNTHCTL_EL2, 0));  // EL1 physical timer access
        cb.emit(msr(CNTVOFF_EL2, A64.XZR));                        // CNTVOFF_EL2 = 0
        set64(cb, 0, 0x30D0_0800L); cb.emit(msr(SCTLR_EL1, 0));    // MMU/caches off, RES1 bits set
        set64(cb, 0, 0x3C5L);       cb.emit(msr(SPSR_EL2, 0));     // target PSTATE = EL1h, DAIF masked
        int elrSlot = cb.reserveAddr(0); cb.emit(msr(ELR_EL2, 0)); // ELR_EL2 = &el1
        cb.emit(eret());

        // === el1: running at EL1 now ===
        int el1 = cb.wordCount();
        set64(cb, 0, 0x30_0000L); cb.emit(msr(CPACR_EL1, 0)); cb.emit(isb()); // FPEN=0b11
        set64(cb, 0, 0x8_0000L);  cb.emit(movToSp(0));                        // SP = 0x80000

        // === mini-UART bring-up (AUX / UART1) ===
        store32(cb, AUX_ENABLES,      1);                 // enable mini-UART
        store32(cb, AUX_MU_CNTL_REG,  0);                 // tx/rx off during config
        store32(cb, AUX_MU_IER_REG,   0);
        store32(cb, AUX_MU_LCR_REG,   3);                 // 8-bit
        store32(cb, AUX_MU_MCR_REG,   0);
        store32(cb, AUX_MU_IIR_REG,   0xC6);              // clear tx/rx FIFOs
        store32(cb, AUX_MU_BAUD_REG,  BAUD_115200);
        store32(cb, GPFSEL1, (ALT5 << 12) | (ALT5 << 15)); // GPIO14/15 -> ALT5
        store32(cb, GPIO_PUP_PDN_CNTRL_REG0, 0);           // no pull on GPIO0..15
        store32(cb, AUX_MU_CNTL_REG,  3);                  // enable tx + rx

        // === print loop: x1=ptr, x2=count, x4=LSR addr, x5=IO addr ===
        int strSlot = cb.reserveAddr(1);
        set64(cb, 2, MESSAGE.length);
        set64(cb, 4, AUX_MU_LSR_REG);
        set64(cb, 5, AUX_MU_IO_REG);

        int loop = cb.wordCount();
        int cbzToDone = cb.emit(cbz(2, 0));               // count==0 => done
        cb.emit(ldrb(0, 1, 0));                           // w0 = *ptr
        int poll = cb.emit(ldrw(3, 4, 0));                // w3 = LSR
        cb.emit(tbz(3, LSR_TX_EMPTY, rel(poll, cb.wordCount()))); // TX not ready -> re-read
        cb.emit(strb(0, 5, 0));                           // *IO = w0
        cb.emit(addImm(1, 1, 1));                         // ptr++
        cb.emit(subImm(2, 2, 1));                         // count--
        cb.emit(A64.b(rel(loop, cb.wordCount())));        // back to loop

        int done = cb.wordCount();
        cb.emit(wfe());
        cb.emit(A64.b(-4));                               // park: spin on wfe

        // === string data, then absolute addresses backpatched ===
        long strAddr = cb.here();
        emitBytes(cb, MESSAGE);

        cb.set(tbzToEl1, tbz(0, 3, rel(el1, tbzToEl1)));  // forward branch to el1
        cb.patchAddr(elrSlot, 0, cb.pcAt(el1));           // ELR_EL2 = &el1
        cb.set(cbzToDone, cbz(2, rel(done, cbzToDone)));  // forward branch to done
        cb.patchAddr(strSlot, 1, strAddr);                // x1 = &message

        return cb;
    }

    /** Byte offset from the instruction at {@code fromIdx} to word {@code toIdx}. */
    private static int rel(int toIdx, int fromIdx) { return (toIdx - fromIdx) * 4; }

    /** Materialize a 64-bit constant into x{@code rd}. */
    private static void set64(CodeBuffer cb, int rd, long value) {
        cb.emitAll(A64.loadImm64(rd, value));
    }

    /** Store a 32-bit {@code value} to MMIO {@code addr} (clobbers x0, x1). */
    private static void store32(CodeBuffer cb, long addr, long value) {
        set64(cb, 0, addr);
        set64(cb, 1, value);
        cb.emit(strw(1, 0, 0));
    }

    /** Append raw bytes as little-endian words (zero-padded to a word). */
    private static void emitBytes(CodeBuffer cb, byte[] bytes) {
        int padded = (bytes.length + 3) & ~3;
        for (int i = 0; i < padded; i += 4) {
            int w = 0;
            for (int b = 0; b < 4; b++) {
                int idx = i + b;
                int v = idx < bytes.length ? (bytes[idx] & 0xFF) : 0;
                w |= v << (b * 8);
            }
            cb.emit(w);
        }
    }

    private EmitBoot() {}
}
