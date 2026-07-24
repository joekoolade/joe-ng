# Custom Pi 4 armstub — group-1 the PPIs so the GIC can deliver the timer

The stock firmware GIC armstub leaves the ARM generic-timer PPIs (INTID 26/27/29/30)
as **group-0 / secure**, so a non-secure EL1/EL2 kernel can never receive them through
the GIC-400 — the interrupt fires and pends at the distributor but is undeliverable.
(Confirmed on real HW: the pristine `GICD_IGROUPR0` reads back `0x00000000`.)

`armstub8-joe.S` is the official Raspberry Pi armstub with `setup_gic` extended to put
**every** interrupt into group 1 — including `IGROUPR0` (the banked SGIs/PPIs), so PPI 30
(the non-secure EL1 physical timer, `CNTP_EL0`) becomes group-1 and reaches non-secure EL1
as an IRQ. See the header comment for the exact two-line diff from stock.

## Build

Needs Homebrew `llvm` + `lld`:

```
brew install llvm lld
make            # -> armstub8-joe.bin (~328 bytes)
```

## Install on the SD card

Copy the binary to the **boot** partition and point `config.txt` at it:

```
cp armstub8-joe.bin  /Volumes/bootfs/armstub8-joe.bin
```

In `config.txt` (keep `enable_gic=1`):

```
enable_gic=1
armstub=armstub8-joe.bin
```

`kernel8.img` is unchanged — the VM already targets PPI 30 through the GIC
(`board.bcm2711.Gic`, `vm.VM.startTimerTick`). After boot, the console prints e.g.
`timer: 99 ticks in 100ms (CNTP -> GIC PPI 30 -> EL1 IRQ)`, proving the periodic
tick is delivered and serviced at EL1.

## Diagnostic builds (not needed in normal use)

The same source builds three diagnostic stubs, each hanging at a checkpoint so a
dark board answers one question (see the `#ifdef`s and the git history for the
bring-up story):

```
make loop    # armstub8-loop.bin  — hang at _start: is our stub loaded at all?
make el3     # armstub8-el3.bin   — hang iff at EL3: did we enter secure?
make probe   # armstub8-probe.bin — full setup + read IGROUPR0 back at EL3 into
             #                       scratch 0x700000 (needs the matching kernel dump)
```
