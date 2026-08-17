package vm;

import board.bcm2711.Bcm2711;
import board.bcm2711.Gic;
import magic.Magic;

/**
 * An interrupt-driven console device over the mini-UART — the clean read/write layer on top of the
 * RX/TX ring buffers, the GIC (SPI 125) and the semaphore primitives. Tasks call {@link #readLine} /
 * {@link #write} and block or queue transparently; none of them touch the UART registers.
 *
 * <p>The mini-UART interrupt is serviced by {@code VM.schedule}, which calls {@link #onIrq}: it fills
 * the RX buffer (and returns true so the scheduler posts {@link #RX_SEM}, waking a blocked reader) and
 * drains the TX buffer into the transmit FIFO. So a read blocks until a keystroke arrives and a write
 * returns immediately, the bytes leaving via the ISR — no busy-waiting either way.
 */
public final class Console
{
    private Console() {}

    static final int N = 64;                 // ring-buffer size (power of two)
    static final int RX_SEM = 2;             // VM scheduler semaphore posted when RX data arrives

    static byte[] rxBuf, txBuf;              // ISR writes rxHead / reads txTail; tasks read rxTail / write txHead
    static int rxHead, rxTail, txHead, txTail;
    static int txIrqCount;                   // TX interrupts serviced (diagnostic)

    /** Allocate the ring buffers and enable the mini-UART RX interrupt through the GIC. */
    static void init()
    {
        rxBuf = new byte[N];
        txBuf = new byte[N];
        rxHead = 0;
        rxTail = 0;
        txHead = 0;
        txTail = 0;
        txIrqCount = 0;
        Gic.initSpi(Bcm2711.AUX_SPI);
        Magic.store32(Bcm2711.AUX_MU_IER_REG, Bcm2711.IER_RX_ENABLE);
    }

    /**
     * ISR body (called from the scheduler on the AUX interrupt): drain received bytes into the RX ring
     * and push queued bytes into the TX FIFO. Returns true if any RX byte arrived, so the caller can
     * post {@link #RX_SEM}. Runs in interrupt context — no blocking.
     */
    static boolean onIrq()
    {
        boolean gotRx = false;
        while ((Magic.load32(Bcm2711.AUX_MU_LSR_REG) & (1 << Bcm2711.LSR_RX_READY)) != 0)
        {
            rxBuf[rxHead] = (byte) Magic.load32(Bcm2711.AUX_MU_IO_REG);
            rxHead = (rxHead + 1) & (N - 1);
            gotRx = true;
        }
        if (txTail != txHead)
        {
            txIrqCount = txIrqCount + 1;
            while (txTail != txHead && (Magic.load32(Bcm2711.AUX_MU_LSR_REG) & (1 << Bcm2711.LSR_TX_EMPTY)) != 0)
            {
                Magic.store32(Bcm2711.AUX_MU_IO_REG, txBuf[txTail] & 0xFF);
                txTail = (txTail + 1) & (N - 1);
            }
            if (txTail == txHead)                          // queue drained: stop the TX interrupt re-firing
            {
                Magic.store32(Bcm2711.AUX_MU_IER_REG, Bcm2711.IER_RX_ENABLE);
            }
        }
        return gotRx;
    }

    /** Queue one byte for interrupt-driven transmission (returns immediately, no FIFO busy-wait). */
    static void putc(byte b)
    {
        Magic.disableIrq();
        txBuf[txHead] = b;
        txHead = (txHead + 1) & (N - 1);
        Magic.enableIrq();
        Magic.store32(Bcm2711.AUX_MU_IER_REG, Bcm2711.IER_RX_ENABLE | Bcm2711.IER_TX_ENABLE);
    }

    /** Queue {@code len} bytes of {@code msg} from {@code off} for transmission. */
    static void write(byte[] msg, int off, int len)
    {
        int i = 0;
        while (i < len)
        {
            putc(msg[off + i]);
            i = i + 1;
        }
    }

    /** Queue a whole byte array for transmission. */
    static void write(byte[] msg)
    {
        write(msg, 0, msg.length);
    }

    /** Block until a byte is available in the RX ring, then return it. */
    static byte readChar()
    {
        while (rxTail == rxHead)
        {
            VMScheduler.semWait(RX_SEM);                            // park until the RX ISR posts
        }
        byte b = rxBuf[rxTail];
        rxTail = (rxTail + 1) & (N - 1);
        return b;
    }

    /**
     * Block reading a line into {@code buf} (up to {@code max} bytes), echoing each character, until a
     * CR or LF. Returns the number of bytes read (excluding the terminator).
     */
    static int readLine(byte[] buf, int max)
    {
        int n = 0;
        while (n < max)
        {
            byte b = readChar();
            if (b == 0x0D || b == 0x0A)                    // CR / LF ends the line
            {
                break;
            }
            buf[n] = b;
            n = n + 1;
            putc(b);                                       // echo the keystroke (interrupt-driven)
        }
        putc((byte) 0x0A);                                 // echo the newline
        return n;
    }
}
