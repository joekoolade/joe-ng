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

clean:
	rm -f armstub8-joe.o armstub8-joe.elf armstub8-joe.bin

.PHONY: all clean
