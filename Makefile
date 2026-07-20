# joe-ng build — front door.
#
# Java cross-compiles a whole source set (per-file make rules fight javac), so
# this is a task runner with a stamp for skip-if-unchanged rather than a per-file
# incremental build. Standard host tools only (make, javac, java) — nothing joe-ng
# itself depends on; the VM is still all our own Java (PLAN.md §0).
#
# Targets:
#   make          compile, run tests, emit kernel8.img   (default)
#   make build    compile the source tree
#   make test     run the unit tests
#   make image    emit kernel8.img (multi-class runtime, compiled from bytecode)
#   make qemu     boot the image in QEMU and assert the banner (test aid, not truth)
#   make clean    remove build artifacts

JAVAC   ?= javac
JAVA    ?= java
OUT     := out
IMG     := kernel8.img
SOURCES := $(shell find src test -name '*.java')

.PHONY: all build test image qemu clean

all: test image

build: $(OUT)/.stamp

# Recompile the whole set whenever any source is newer than the stamp.
$(OUT)/.stamp: $(SOURCES)
	@mkdir -p $(OUT)
	$(JAVAC) -d $(OUT) $(SOURCES)
	@touch $@

test: build
	$(JAVA) -cp $(OUT) asm.A64Test
	$(JAVA) -cp $(OUT) objectmodel.ObjectModelTest
	$(JAVA) -cp $(OUT) classfile.ClassReaderTest $(OUT)
	$(JAVA) -cp $(OUT) compiler.CompilerTest $(OUT)

image: build
	$(JAVA) -cp $(OUT) writer.BuildRuntimeImage $(OUT) $(IMG)
	@ls -l $(IMG)

qemu: image
	sh scripts/qemu-check.sh $(IMG)

clean:
	rm -rf $(OUT) $(IMG)
