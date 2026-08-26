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
# Host tools + host-side unit tests. JDK test programs under test/jdk are GUEST programs (compiled against the
# java.base overlay by the `jdktests` target), not host code, so exclude them here.
SOURCES := $(shell find src test -name '*.java' -not -path 'test/jdk/*')
# Guest sources (the mini java.base + the demand-loaded demo). Compiled as a java.base
# patch so java/lang/* carry their real names; embedded raw and loaded on the metal (M4).
# --add-reads lets the patched java.base see magic.Magic (the scheduler intrinsics).
GUESTSRC := $(shell find guestsrc -name '*.java')

.PHONY: all build test image qemu clean

all: test image

build: $(OUT)/.stamp guest

# Recompile the whole set whenever any source is newer than the stamp.
$(OUT)/.stamp: $(SOURCES)
	@mkdir -p $(OUT)
	$(JAVAC) -d $(OUT) $(SOURCES)
	@touch $@

# Compile the guest tree into out/ after the main set (it references magic.Magic there).
# ALWAYS purge the guest output packages (java/ jdk/ demo/ come only from guestsrc) before recompiling: an
# incremental javac never deletes the .class of a REMOVED source, so a retired guest class (e.g. a mini
# java/lang/String swapped for stock) would leave a stale .class that registerTree keeps embedding — the build
# would silently keep running the old class. The guest tree is small; a clean recompile each build is cheap and
# keeps "delete the source" honest.
.PHONY: guest
guest: $(OUT)/.stamp
	rm -rf $(OUT)/java $(OUT)/jdk $(OUT)/demo
	$(JAVAC) --patch-module java.base=guestsrc --add-reads java.base=ALL-UNNAMED -cp $(OUT) -d $(OUT) $(GUESTSRC)

test: build
	$(JAVA) -cp $(OUT) asm.A64Test
	$(JAVA) -cp $(OUT) objectmodel.ObjectModelTest
	$(JAVA) -cp $(OUT) classfile.ClassReaderTest $(OUT)
	$(JAVA) -cp $(OUT) classfile.RefMapTest $(OUT)
	$(JAVA) --add-opens java.base/java.lang=ALL-UNNAMED -cp $(OUT) compiler.CompilerTest $(OUT)
	$(JAVA) -cp $(OUT) crypto.CryptoTest
	$(JAVA) -cp $(OUT) zip.ZipTest

# Unmodified JDK tests run as manifest mains: compiled against the guest java.base overlay into the classDir.
# They are unnamed-package, so they can't join the guestsrc --patch-module set -- compile them separately.
# Add files to JDKTESTS to embed more. (Demand-loaded: only pulled when named as the manifest main.)
JDKTESTS ?= test/jdk/java/lang/Thread/GenerifyStackTraces.java test/jdk/java/lang/Thread/HoldsLock.java test/jdk/java/lang/Thread/IsAlive.java test/jdk/java/lang/Thread/ITLConstructor.java test/jdk/java/lang/Thread/JoinWithDuration.java test/jdk/java/lang/Thread/JoinWithDurationRun.java test/jdk/java/lang/Thread/MainThreadTest.java test/jdk/java/lang/Thread/NullStackTrace.java test/jdk/java/lang/Thread/SleepSanity.java test/jdk/java/lang/Thread/SleepSanityRun.java test/jdk/java/lang/Thread/SleepWithDuration.java test/jdk/java/lang/Thread/SleepWithDurationRun.java test/jdk/java/lang/Compare.java test/jdk/testlib/RandomFactory.java test/jdk/java/lang/Long/BitTwiddle.java test/jdk/java/util/concurrent/atomic/Lazy.java test/jdk/java/util/concurrent/atomic/AtomicUpdaters.java test/jdk/java/util/concurrent/ConcurrentMap/ConcurrentModification.java test/jdk/java/util/zip/DeflaterClose.java test/jdk/java/util/zip/InflaterClose.java test/jdk/java/util/zip/DataDescriptorIgnoreCrcAndSizeFields.java test/jdk/java/util/zip/DataDescriptorSignatureMissing.java test/jdk/java/util/zip/GZIP/GZIPInputStreamAvailable.java test/jdk/java/util/zip/ZipOutputStream/CloseWrappedStream.java test/jdk/java/util/zip/ZipInputStream/Zip64DataDescriptor.java test/jdk/java/util/zip/ZipJUnitAll.java

.PHONY: jdktests
jdktests: guest
	$(JAVAC) -implicit:none -sourcepath '' --patch-module java.base=guestsrc --add-reads java.base=ALL-UNNAMED -cp $(OUT) -d $(OUT) $(JDKTESTS)

# M4: external "plugin" classes compiled into ramfs/plugins/ (a generated, gitignored subtree) -- NOT into the
# classDir (out/). On the metal they exist ONLY as files the guest reads + defineClass'es at runtime, never
# reachable by name via forName. Compiled with plain javac (seed JDK) since they're dependency-free.
PLUGINSRC := $(shell find plugins-src -name '*.java' 2>/dev/null)
.PHONY: plugins
plugins:
	@if [ -n "$(PLUGINSRC)" ]; then mkdir -p ramfs/plugins && $(JAVAC) -d ramfs/plugins $(PLUGINSRC); fi

# The classpath jar: an ordinary Java program compiled OUTSIDE the classDir (out/) and packaged with the seed
# JDK's `jar` tool into ramfs/lib/app.jar. It is deliberately NOT embedded as a class -- on the metal it exists
# only inside the archive, and /etc/init's `classpath=` line is what makes it loadable (vm/JarFs).
JARSRC := $(shell find jarsrc -name '*.java' 2>/dev/null)
JARCLASSES := $(OUT)/../.appjar
.PHONY: appjar
appjar: guest
	@if [ -n "$(JARSRC)" ]; then \
	  rm -rf $(JARCLASSES) && mkdir -p $(JARCLASSES) ramfs/lib && \
	  $(JAVAC) -implicit:none -sourcepath '' --patch-module java.base=guestsrc --add-reads java.base=ALL-UNNAMED -cp $(OUT) -d $(JARCLASSES) $(JARSRC) && \
	  jar --create --file ramfs/lib/app.jar --main-class app.Main -C $(JARCLASSES) . && \
	  ls -l ramfs/lib/app.jar; fi

image: build jdktests plugins appjar
	$(JAVA) --add-opens java.base/java.lang=ALL-UNNAMED -cp $(OUT) writer.BuildRuntimeImage $(OUT) $(IMG)
	@ls -l $(IMG)

qemu: image
	sh scripts/qemu-check.sh $(IMG)

clean:
	rm -rf $(OUT) $(IMG) $(JARCLASSES)
