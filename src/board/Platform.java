/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-19
 */
package board;

/**
 * The contract every board's platform class meets: the operations whose implementation depends on the machine
 * (block device, entropy, reboot, the console UART, the interrupt controller, the network link, ...), as STATIC methods of one class per board -- {@code board.bcm2711.Bcm2711Platform},
 * {@code board.virt.VirtPlatform}, and later a PC's.
 *
 * <p><b>The choice is made when the image is built, not when it runs.</b> {@code BuildRuntimeImage --board}
 * names the platform class ({@link #classFor}); the writer roots each method of {@link #OPS} in that class and
 * stores its compiled address in the {@code vm/VM} field the native links to. So a running VM has no
 * {@code if (board)} on these paths and no virtual dispatch, and the other boards' drivers are not in the image at
 * all -- a stray reference to one fails at link time by name instead of touching an unmapped device window.
 *
 * <p><b>The writer refuses an incomplete platform.</b> A platform class missing any method of {@link #OPS}, at
 * exactly that descriptor, stops the build naming it -- the completeness an interface would give, without
 * dispatch. A board that LACKS a device still declares the method and answers that explicitly (as
 * {@code VirtPlatform.blockInit} answers {@link #BLOCK_NO_DEVICE}); there is no inherited default to fall silently
 * into.
 */
public final class Platform
{
    private Platform()
    {
    }

    /** Board ids, as {@code boardId()} answers them; the guest's {@code MetalBoard.board()} names them. */
    public static final long BOARD_BCM2711 = 0L;
    public static final long BOARD_VIRT = 1L;

    /** {@code blockInit()}'s answer on a board with no block device: the guest throws it as an IOException. */
    public static final long BLOCK_NO_DEVICE = -100L;

    /**
     * The operations: method name + descriptor in the platform class, and the static that holds its compiled
     * address (what {@code Loader}'s native lookup hands out, or what the VM calls through). Every argument and
     * result is a {@code long}: these are reached by {@code Magic.call0}/{@code call2}, which pass and return
     * whole registers.
     */
    public static final String[][] OPS = {
        { "boardId()J",                                   "vm/VM.boardIdAddr" },            // MetalBoard.boardId0
        { "setRebootFlags(J)J",                           "vm/VM.boardRebootFlagsAddr" },   // MetalBoard.setRebootFlags0
        { "reboot()V",                                    "vm/VM.boardRebootAddr" },        // MetalBoard.reboot0
        { "blockInit()J",                                 "vm/VM.blockInitAddr" },          // MetalBlock.init0
        { "blockRead(JLmagic/ObjectReference;JJ)J",       "vm/VM.blockReadAddr" },          // MetalBlock.read0
        { "blockWrite(JLmagic/ObjectReference;JJ)J",      "vm/VM.blockWriteAddr" },         // MetalBlock.write0
        { "entropy(Lmagic/ObjectReference;J)J",           "vm/VM.hwEntropyAddr" },          // MetalRamfs.hwEntropy0
        { "entropyReport()V",                             "vm/VM.entropyReportAddr" },      // the boot "hw rng:" line
        // The console UART's register half; board.Uart (rings, locks, interrupt body) calls these.
        { "consoleInit()V",                               "board/Uart.hwInitAddr" },
        { "consoleTxReady()J",                            "board/Uart.hwTxReadyAddr" },
        { "consoleRxReady()J",                            "board/Uart.hwRxReadyAddr" },
        { "consoleGet()J",                                "board/Uart.hwGetAddr" },
        { "consolePut(J)V",                               "board/Uart.hwPutAddr" },
        { "consoleSetIrq(J)V",                            "board/Uart.hwSetIrqAddr" },
        { "consoleSpi()J",                                "board/Uart.hwSpiAddr" },
        // The interrupt controller and the board's device interrupts; board.Irq calls these.
        { "irqInitTimer()J",                              "board/Irq.hwInitTimerAddr" },
        { "irqAck()J",                                    "board/Irq.hwAckAddr" },
        { "irqEnd(J)V",                                   "board/Irq.hwEndAddr" },
        { "irqEnable(J)V",                                "board/Irq.hwEnableAddr" },
        { "irqMask(J)V",                                  "board/Irq.hwMaskAddr" },
        { "irqUnmask(J)V",                                "board/Irq.hwUnmaskAddr" },
        { "irqDevice(J)J",                                "board/Irq.hwDeviceAddr" },
        // The network link (Pi: the CYW43455 WiFi chip; virt: virtio-net); net.Link calls these.
        { "netPresent()J",                                "net/Link.hwPresentAddr" },
        { "netUp(J)V",                                    "net/Link.hwUpAddr" },
        { "netSend(JJ)V",                                 "net/Link.hwSendAddr" },
        { "netMac()J",                                    "net/Link.hwMacAddr" },
        { "netWaitFrame(JJJ)J",                           "net/Link.hwWaitFrameAddr" },
        { "netChannel(JJ)J",                              "net/Link.hwChannelAddr" },
        { "netEthOffset(JJ)J",                            "net/Link.hwEthOffsetAddr" },
        { "netEvent(JJ)V",                                "net/Link.hwEventAddr" },
        { "netLinkFrame(JJ)V",                            "net/Link.hwLinkFrameAddr" },
        { "netLinkStats(JJJ)J",                           "net/Link.hwLinkStatsAddr" },
    };

    /** The platform class (internal name) for a {@code --board} name, or null for an unknown board. */
    public static String classFor(String board)
    {
        if (board.equals("pi4"))
        {
            return "board/bcm2711/Bcm2711Platform";
        }
        if (board.equals("virt"))
        {
            return "board/virt/VirtPlatform";
        }
        return null;
    }
}
