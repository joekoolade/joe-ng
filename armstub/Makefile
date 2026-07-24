# Build the custom Pi 4 armstub (armstub8-joe.bin) with the LLVM toolchain.
# Requires Homebrew llvm (clang, llvm-objcopy) + lld (ld.lld).
LLVM   := /opt/homebrew/opt/llvm/bin
LLD    := /opt/homebrew/opt/lld/bin
CLANG  := $(LLVM)/clang
OBJCOPY:= $(LLVM)/llvm-objcopy
LD     := $(LLD)/ld.lld
TARGET := aarch64-none-elf

all: armstub8-joe.bin

armstub8-joe.o: armstub8-joe.S
	$(CLANG) -target $(TARGET) -c $< -o $@

armstub8-joe.elf: armstub8-joe.o armstub.ld
	$(LD) -T armstub.ld -o $@ $<

armstub8-joe.bin: armstub8-joe.elf
	$(OBJCOPY) -O binary $< $@

# Diagnostic: same stub but hangs immediately at _start. If it loads, the board
# stays dark; if the banner prints, the firmware ignored armstub=.
loop: armstub8-loop.bin

armstub8-loop.o: armstub8-joe.S
	$(CLANG) -target $(TARGET) -DLOOPTEST -c $< -o $@

armstub8-loop.elf: armstub8-loop.o armstub.ld
	$(LD) -T armstub.ld -o $@ $<

armstub8-loop.bin: armstub8-loop.elf
	$(OBJCOPY) -O binary $< $@

# Diagnostic: hang only if entered at EL3. Board dark => EL3 (secure, good);
# banner prints => not EL3 (why the group-1 IGROUPR write is RAZ/WI).
el3: armstub8-el3.bin

armstub8-el3.o: armstub8-joe.S
	$(CLANG) -target $(TARGET) -DEL3TEST -c $< -o $@

armstub8-el3.elf: armstub8-el3.o armstub.ld
	$(LD) -T armstub.ld -o $@ $<

armstub8-el3.bin: armstub8-el3.elf
	$(OBJCOPY) -O binary $< $@

clean:
	rm -f armstub8-joe.o armstub8-joe.elf armstub8-joe.bin \
	      armstub8-loop.o armstub8-loop.elf armstub8-loop.bin \
	      armstub8-el3.o armstub8-el3.elf armstub8-el3.bin

.PHONY: all clean
