/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import board.Uart;
import magic.Address;
import magic.DataReferenceArray;
import magic.DataReference;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u2;

/**
 * Interface dispatch (JikesRVM: {@code InterfaceInvocation}): the per-interface method registry ({@code if*}), each
 * class's directly-declared interfaces and their transitive closure, itables and the per-class imaps refilled as
 * classes arrive, default-method selection (JVMS 5.4.3.3 most-specific), and resolving a call through a receiver's
 * itable directory. Split out of {@link Loader}.
 */
final class InterfaceInvocation
{
    private InterfaceInvocation()
    {
    }

    // Directly-declared interfaces per registered class/interface, as registry indices, flat:
    // clIfaceReg[r*MAX_DIRECT_IF + j], j < clIfaceRegN[r]. buildItableDir transitively closes over these so
    // an itable directory keys on the SUPER-interfaces too (e.g. ArrayList implements List extends Iterable
    // -> its directory has both List and Iterable, so invokeinterface Iterable.iterator resolves).
    static final int MAX_DIRECT_IF = 8;
    static int[] clIfaceReg;
    static int[] clIfaceRegN;
    static int[] ifClosureBuf;   // scratch: registry indices in the current class's transitive iface closure
    // Every instantiated class's imap (the shared itable), captured at fillTib so refillImaps can repair
    // default-method slots that were 0 because the interface holding the default hadn't been EMITTED yet when
    // this class's buildImap ran (phase B is superclass-first, not super-interface-first).
    static final int MAXIMAP = 2048;
    static DataReferenceArray instImaps;   // itable directories (allocData payloads): data references
    static int[] instImapReg;    // the class registry index each imap belongs to (for its iface closure)
    static int instImapN;
    /**
     * Per-imap "nothing left to repair" flag, and it is EXACT rather than approximate.
     *
     * <p>{@link #refillItable} only ever writes a slot that is 0 -- it never overwrites a filled one. So an
     * imap whose slots are all non-zero is a guaranteed NO-OP on every future pass, and skipping it loses
     * nothing. An imap that still has a hole stays hot, because the hole may become fillable once the class
     * declaring the default is compiled by a later batch, which is the whole reason this pass exists.
     *
     * <p>MEASURED: without it, {@code refillImaps} was 463 SECONDS of a 900-second launcher load -- it walked
     * all ~830 imaps every batch and called {@code ifaceClosureOf} on each, whether or not anything needed
     * repairing.
     */
    static boolean[] instImapDone;
    /**
     * Per-imap memo of each slot's MAXIMALLY SPECIFIC DECLARER ({@link #mostSpecificDeclarer}), -2 = not yet
     * computed. A hole that stays a hole is re-examined on every batch, and that pass is what it cost.
     *
     * <p>MEASURED on SslEngineProbe (2026-11-04): {@code refillImaps} was 20.5 s of a 283 s run, 12.2 s of the
     * first TLS session. It called {@code defaultBySig} 1,777,957 times; EVERY call found a declarer and only
     * 1,303 then found a body -- the slot's method is abstract in its most specific declarer, so the hole is
     * permanent, and each batch re-asked ~4.3 closure interfaces, hashing the same name and descriptor for each,
     * to arrive at the same declarer.
     *
     * <p>SOUND because the declarer is a function of two things: what each interface DECLARES (its own method
     * run and {@code ifDecl}, fixed when the interface registers) and the class's interface CLOSURE. The closure
     * can grow -- a superclass registered after its subclass resolves {@link ClassRegistry#superRegOf} late and brings its
     * interfaces -- but only by appending, so its size {@link #instImapDeclN} changes whenever it does, and a
     * changed size discards the memo. The body lookup itself ({@link Loader#regBodyIn}) is NOT memoised: that answer
     * really does change, which is why the hole is revisited at all.
     */
    static int[][] instImapDecl;
    static int[] instImapDeclN;                 // the closure size instImapDecl[m] was computed for
    /**
     * WHY refillImaps costs what it does, counted rather than inferred. The lazy-closure change measured as a
     * no-op on hardware (imap 462,948ms -> 460,288ms at batch 165), and there are two candidate reasons that
     * look identical from a timing line alone: the skip never fires, or the run never carried the change.
     * These separate them -- {@code rfSkip} is the memo working, {@code rfClos} is the cost it was meant to
     * avoid. Cumulative over the boot, printed with the per-batch cost line.
     */
    static int rfSkip;
    static int rfVisit;
    static int rfClos;
    static int rfHoleEnd;
    // Interface-method registry. Every distinct interface method (name+descriptor)
    // gets a global index; each implementing class then carries an "imap" indexed by
    // it, holding that class's implementation. This is the itable: it decouples the
    // call site from where the method happens to sit in a given class's vtable, so
    // two classes implementing the same interface at different vtable slots both
    // dispatch correctly. Interfaces are loaded before their implementors.
    static final int MAXIFM = 8192;   // flattened per-interface runs duplicate inherited signatures
                                              // (2048 overflowed once the console launcher reached picocli's
                                              //  @ArgGroup members, whose closure is interface-dense)
    static AddressArray ifBase;        // interface blob holding the signature
    static int[] ifNameOff;
    static int[] ifDescOff;
    // The blob of the interface that DECLARES this entry within its run: the run's own interface when it
    // declares (or redeclares) the signature itself, else the ancestor whose run it was copied from. ifBase
    // cannot answer this -- a redeclaration keeps the inherited entry (and its base) to keep the slot number.
    // Default selection needs it: JVMS 5.4.3.3 picks the MAXIMALLY SPECIFIC declaring interface.
    static AddressArray ifDecl;
    static int ifCount;
    static int rfClosSteps;                             // ifaceClosureOf interface visits
    static int rfFillSteps;                             // refillItable slot reads
    static int rfDbsCalls;                              // defaultBySig calls (one per still-0 slot per batch)
    static int rfProbeSteps;                            // ... closure interfaces probed inside them
    static int rfHashNameSteps;                         // ... METHOD-name bytes folded -- the irreducible half,
                                                        //   since an FNV fold can only cache its PREFIX
    static int rfChainSteps;                            // ... bucket-chain entries compared
    /**
     * Resolve {@code name+desc} through the interfaces named in a Type's ITABLE DIRECTORY, for a receiver
     * whose class is not in the registry at all.
     *
     * <p>A SYNTHESISED LAMBDA is exactly that receiver: {@code finishLambdaClass} builds it a Type and an
     * itable directory but no {@code clTab} entry, so {@code classRegByType} answers -1 and the class-chain
     * and {@code resolveViaInterfaces} tiers -- both keyed on a registry index -- cannot run.
     *
     * <p>That matters because a lambda can carry DEFAULT methods. {@code java.util.stream.Sink} has one
     * abstract method ({@code accept}, inherited from {@code Consumer}) and defaults for {@code begin},
     * {@code end} and {@code cancellationRequested}, so javac happily makes a lambda a Sink -- and the stream
     * pipeline then calls {@code downstream.begin(size)} on it from
     * {@code Sink$ChainedReference.begin}. The lambda's itable holds a thunk for the SAM only; the default
     * body lives on the interface, which the directory names and nothing else did.
     *
     * <p>The interface's own super-interfaces are searched too, since a default can be inherited (Sink's
     * {@code accept} comes from Consumer).
     */
    /** Trace every resolution the itable-directory tier makes: which interface, which member, what address. */
    private static final boolean ITABLE_DIR_WATCH = false;
    /**
     * Index over the interface-method runs, keyed on (run start, name, descriptor): chained, filled as each
     * entry is appended (an entry's run start is known only then), and cleared with the table in resetLoader.
     *
     * <p>{@link #ifmSlotAbs} and {@link #ifmSlotIn} SCANNED the interface's run comparing name and descriptor
     * byte by byte, and {@code mostSpecificDeclarer} asks once per interface of a class's closure. Profiled on
     * InvokeGapProbe after #452 (scripts/qprof-tally.pl caller view): the top remaining caller of the byte
     * compares, 8.2% of the busy core. A run holds each signature at most once ({@link #ifmAppendUnique}), so
     * a hit in the right run is the one entry the scan would have found.
     */
    private static final int IFTAB = 8192;              // power of two >= MAXIFM; chained
    static int[] ifBucket;                      // (run start, name, desc) hash -> newest entry, -1 if none
    private static int[] ifNext;                        // entry -> next older entry with the same hash
    /** Cap on the interface names virtualResolve's default-method tier will consider for one receiver. */
    static final int MAXIFACEFALLBACK = 64;

    /** {@code run()V} via the receiver's itable entry for java/lang/Runnable, or 0. Bounded, unlike the
     *  hand-emitted scan it replaces: it stops at the 0 terminator instead of walking past it. */
    static long runFromItable(ObjectReference recv)
    {
        return ifaceMethodFromItable(recv, Loader.runnableTypeAddr().toAddress(), Loader.runnableRunSlot());
    }

    /** Slot {@code slot} of the receiver's itable run for interface {@code want}, or 0 (also 0 for want == 0). */
    static long ifaceMethodFromItable(ObjectReference recv, Address want, int slot)
    {
        if (want.isZero())
        {
            return 0L;
        }
        long tib = recv.toAddress().loadLong();
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return 0L;
        }
        long type = Address.fromLong(tib).loadLong();
        if (type <= 0x1000L)
        {
            return 0L;
        }
        Address dir = Address.fromLong(type).plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).loadAddress();
        if (dir.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int n = 0;
        while (n < MAXIFACEFALLBACK)
        {
            Address ent = dir.plus(n * (long) ObjectModel.ITABLE_ENTRY_SIZE).loadAddress();
            if (ent.isZero())
            {
                return 0L;                              // the sentinel: STOP (the old scan ran past it)
            }
            if (ent.EQ(want))
            {
                long it = dir.plus(n * (long) ObjectModel.ITABLE_ENTRY_SIZE)
                        .plus(ObjectModel.ITABLE_ENTRY_TABLE_OFFSET).loadLong();
                if (it <= 0x1000L)
                {
                    return 0L;
                }
                return Address.fromLong(it).plus(slot * 8L).loadLong();
            }
            n += 1;
        }
        return 0L;
    }

    /**
     * Record the current class's directly-declared interfaces (from {@code gView.implIfName}) as registry indices
     * at {@code clCount}, for {@link #buildItableDir}'s transitive closure. Interfaces this class implements
     * are already loaded (dep ordering), so they resolve now.
     */
    /**
     * Does {@code fromType} extend the INTERFACE {@code targetType}, walking direct interfaces transitively?
     *
     * <p>The one subtype relation a Type node cannot express. An interface Type is a chain dead end -- depth
     * -1, no display, no superclass link, and no itable directory of its own -- so {@code VM.typeAssignable}
     * answers class-implements-interface (from the CLASS's itable dir) but not interface-EXTENDS-interface:
     * {@code Collection.isAssignableFrom(List.class)} came back false. The relation is recorded, just
     * elsewhere: {@code captureDirectIfaces} puts every class's and interface's direct interfaces in the
     * class registry.
     *
     * <p>Deliberately consulted ONLY from the reflection path ({@code Class.isAssignableFrom}) and not from
     * {@code typeAssignable} itself. {@code instanceof}/{@code checkcast} always have an OBJECT receiver,
     * whose Type is a class, so they never ask this question -- putting a registry walk in that hot,
     * dispatch-critical routine would buy nothing and risk everything.
     */
    static int ifaceExtends(Address fromType, Address targetType)
    {
        if (fromType.isZero() || targetType.isZero() || ClassRegistry.clTab == null)
        {
            return 0;
        }
        int reg = ClassRegistry.classRegByType(fromType);
        if (reg < 0)
        {
            return 0;
        }
        return extendsIfaceFrom(reg, targetType, 0) ? 1 : 0;
    }

    /** Depth-bounded so a malformed (cyclic) interface graph cannot hang the reflection path. */
    private static boolean extendsIfaceFrom(int reg, Address targetType, int depth)
    {
        if (depth > 8)
        {
            return false;
        }
        int n = clIfaceRegN[reg];
        int i = 0;
        while (i < n)
        {
            int r = clIfaceReg[reg * MAX_DIRECT_IF + i];
            if (r >= 0 && ClassRegistry.clTab[r] != null)
            {
                if (ClassRegistry.clTab[r].type.EQ(DataReference.fromAddress(targetType)))
                {
                    return true;
                }
                if (extendsIfaceFrom(r, targetType, depth + 1))
                {
                    return true;
                }
            }
            i += 1;
        }
        return false;
    }

    static long resolveViaItableDir(Address type, Address nameOff, Address descOff)
    {
        Address dir = type.plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).loadAddress();
        if (dir.isZero())
        {
            return 0L;
        }
        int decl = -2;                                  // the maximally specific declarer, found on first need
        Address entry = dir;
        Address iface = entry.plus(ObjectModel.ITABLE_ENTRY_IFACE_OFFSET).loadAddress();
        while (!iface.isZero())                             // 0 interfaceType terminates the directory
        {
            int ir = ClassRegistry.classRegByType(iface);
            if (ir >= 0 && ir < ClassRegistry.clCount && ClassRegistry.clTab[ir] != null)
            {
                // THE RECEIVER'S OWN ENTRY FIRST. For a lambda the sought method is usually the SAM, which is
                // ABSTRACT on the interface -- there is no body to resolve to, and the implementation is the
                // synthesised THUNK sitting in this very itable. Reading the slot answers that; searching the
                // interface for a body cannot, and reported `DISPATCH ON UNREGISTERED TYPE ... get()` for
                // every Supplier lambda whose call site missed the directory.
                //
                // Correct for defaults too, and preferred for them: buildItableFor already resolves a default
                // to the implementation THIS receiver should run, which is more specific than whatever body
                // the declaring interface happens to carry.
                int slot = ifmSlotAbs(ir, nameOff, descOff);
                if (slot >= 0)
                {
                    Address itable = entry.plus(ObjectModel.ITABLE_ENTRY_TABLE_OFFSET).loadAddress();
                    if (!itable.isZero())
                    {
                        long hit = itable.plus(slot * 8L).loadLong();
                        if (hit != 0L)
                        {
                            return hit;
                        }
                    }
                }
                // An EMPTY slot is resolved to the MAXIMALLY SPECIFIC declaring interface among this receiver's,
                // not to whichever interface the directory lists first: walking in directory order answered
                // Shape.kind for a receiver whose Polygon overrides it whenever Shape's entry came first.
                if (decl == -2)
                {
                    decl = dirDeclarer(dir, nameOff, descOff);
                }
                if (decl >= 0)
                {
                    long d = DynamicLinker.resolveLinkTarget(ClassRegistry.clTab[decl].base.plus(ClassRegistry.clTab[decl].nameOff),
                            nameOff, descOff);
                    if (d != 0L)
                    {
                        return d;
                    }
                }
                long b = DynamicLinker.resolveLinkTarget(ClassRegistry.clTab[ir].base.plus(ClassRegistry.clTab[ir].nameOff),
                        nameOff, descOff);
                if (b != 0L)
                {
                    if (ITABLE_DIR_WATCH)
                    {
                        Uart.write(Magic.bytes("  itabledir "));
                        Loader.printNameAt(ClassRegistry.clTab[ir].base.toAddress(), ClassRegistry.clTab[ir].nameOff);
                        Uart.putc(0x2E);
                        Loader.printNameAt(nameOff, 0);
                        Loader.printNameAt(descOff, 0);
                        Uart.write(Magic.bytes(" -> 0x"));
                        VM.printHex(b);
                        Uart.putc(0x0A);
                    }
                    return b;
                }
                b = VirtualDispatch.resolveViaInterfaces(ir, nameOff, descOff);
                if (b != 0L)
                {
                    if (ITABLE_DIR_WATCH)
                    {
                        Uart.write(Magic.bytes("  itabledir(super) "));
                        Loader.printNameAt(ClassRegistry.clTab[ir].base.toAddress(), ClassRegistry.clTab[ir].nameOff);
                        Uart.putc(0x2E);
                        Loader.printNameAt(nameOff, 0);
                        Loader.printNameAt(descOff, 0);
                        Uart.write(Magic.bytes(" -> 0x"));
                        VM.printHex(b);
                        Uart.putc(0x0A);
                    }
                    return b;
                }
            }
            entry = entry.plus(ObjectModel.ITABLE_ENTRY_SIZE);
            iface = entry.plus(ObjectModel.ITABLE_ENTRY_IFACE_OFFSET).loadAddress();
        }
        return 0L;
    }

    /**
     * {@link #mostSpecificDeclarer} over the interfaces an itable directory lists -- the receiver's TRANSITIVE
     * interface set, so no closure needs recomputing (and the shared closure scratch is not touched).
     */
    private static int dirDeclarer(Address dir, Address nameU, Address descU)
    {
        int[] regs = new int[MAXIFACEFALLBACK];
        int n = 0;
        Address entry = dir;
        Address iface = entry.plus(ObjectModel.ITABLE_ENTRY_IFACE_OFFSET).loadAddress();
        while (!iface.isZero() && n < regs.length)
        {
            regs[n] = ClassRegistry.classRegByType(iface);
            n += 1;
            entry = entry.plus(ObjectModel.ITABLE_ENTRY_SIZE);
            iface = entry.plus(ObjectModel.ITABLE_ENTRY_IFACE_OFFSET).loadAddress();
        }
        return mostSpecificDeclarer(regs, n, nameU, descU);
    }

    static void captureDirectIfaces()
    {
        int n = 0;
        int k = 0;
        while (k < Loader.gView.implIfName.length && n < MAX_DIRECT_IF)
        {
            int r = ClassRegistry.classRegByName(Loader.gView.implIfName[k]);
            if (r >= 0)
            {
                clIfaceReg[ClassRegistry.clCount * MAX_DIRECT_IF + n] = r;
                n += 1;
            }
            k += 1;
        }
        clIfaceRegN[ClassRegistry.clCount] = n;
    }

    /**
     * Capture this interface's FLATTENED method list as a contiguous run in the ifm registry
     * (ifBase/ifNameOff/ifDescOff): each super-interface's flattened run first, in interfaces[]
     * declaration order, then this interface's own virtual methods -- deduped by name+descriptor so
     * a redeclaration keeps the inherited position. This IS the per-interface itable slot numbering,
     * identical to the writer's {@code ClassFile.interfaceMethods} (itparity-checked at load).
     * Flattening (not own-only lists) is what lets a call typed to a super-interface (a
     * BinaryOperator lambda invoked as BiFunction) index the right slot.
     */
    static void registerInterface()
    {
        Loader.gBuild.ifmStart = ifCount;
        int k = 0;
        while (k < Loader.gView.implIfName.length)                        // super-interfaces' flattened runs first
        {
            int r = ClassRegistry.classRegByName(Loader.gView.implIfName[k]);
            if (r >= 0)
            {
                int s = 0;
                while (s < ClassRegistry.clTab[r].ifmCount)
                {
                    int src = ClassRegistry.clTab[r].ifmStart + s;
                    ifmAppendUnique(ifBase.get(src), ifNameOff[src], ifDescOff[src], ifDecl.get(src));
                    s += 1;
                }
            }
            k += 1;
        }
        Address p = Loader.gView.methodsStart;                         // then this interface's own declarations
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (Loader.isVirtual(u2(p), Loader.gView.cp[u2(p.plus(2))]))
            {
                ifmAppendUnique(Loader.gView.base.toAddress(), Loader.gView.cp[u2(p.plus(2))], Loader.gView.cp[u2(p.plus(4))], Loader.gView.base.toAddress());
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        Loader.gBuild.ifmCount = ifCount - Loader.gBuild.ifmStart;
    }

    /**
     * Append (base, name, desc) to the current interface's run unless the signature is in it, recording
     * {@code decl} as the declaring interface's blob. A signature already in the run keeps its entry (and so its
     * slot), but when the interface being registered declares it ITSELF ({@code decl == gView.base}) that entry's
     * declarer becomes this interface: an override of an inherited default must be visible as one.
     */
    private static void ifmAppendUnique(Address base, int nameOff, int descOff, Address decl)
    {
        int i = Loader.gBuild.ifmStart;
        while (i < ifCount)
        {
            if (Loader.utf8EqAt(base, nameOff, ifBase.get(i), ifNameOff[i])
                    && Loader.utf8EqAt(base, descOff, ifBase.get(i), ifDescOff[i]))
            {
                if (decl.EQ(Loader.gView.base.toAddress()))
                {
                    ifDecl.set(i, Loader.gView.base.toAddress());
                }
                return;
            }
            i += 1;
        }
        if (ifCount >= MAXIFM) { Loader.capHalt(Magic.bytes("MAXIFM"), ifCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        ifBase.set(ifCount, base);
        ifNameOff[ifCount] = nameOff;
        ifDescOff[ifCount] = descOff;
        ifDecl.set(ifCount, decl);
        ifIndexEntry(ifCount, Loader.gBuild.ifmStart);
        ifCount += 1;
    }

    private static int ifKey(Address nameBase, int nameOff, Address descBase, int descOff, int runStart)
    {
        return (Utf8.utf8Hash2(nameBase, nameOff, descBase, descOff) ^ (runStart * 0x9E3779B1)) & (IFTAB - 1);
    }

    /** Link entry {@code i}, in the run starting at {@code runStart}, into the index. */
    private static void ifIndexEntry(int i, int runStart)
    {
        if (ifBucket == null)
        {
            ifBucket = new int[IFTAB];
            ifNext = new int[MAXIFM];
            int b = 0;
            while (b < IFTAB)
            {
                ifBucket[b] = -1;
                b += 1;
            }
        }
        int h = ifKey(ifBase.get(i), ifNameOff[i], ifBase.get(i), ifDescOff[i], runStart);
        ifNext[i] = ifBucket[h];
        ifBucket[h] = i;
    }

    /**
     * Slot of the signature (name at {@code nameBase+nameOff}, descriptor at {@code descBase+descOff}) in
     * registered interface {@code r}'s run, or -1: the entry in that run, whose names match, through the index.
     */
    private static int ifmSlotFind(int r, Address nameBase, int nameOff, Address descBase, int descOff)
    {
        int count = ClassRegistry.clTab[r].ifmCount;
        if (count == 0 || ifBucket == null)
        {
            return -1;
        }
        int start = ClassRegistry.clTab[r].ifmStart;
        int i = ifBucket[ifKey(nameBase, nameOff, descBase, descOff, start)];
        while (i >= 0)
        {
            if (i >= start && i < start + count
                    && Loader.utf8EqAt(nameBase, nameOff, ifBase.get(i), ifNameOff[i])
                    && Loader.utf8EqAt(descBase, descOff, ifBase.get(i), ifDescOff[i]))
            {
                return i - start;
            }
            i = ifNext[i];
        }
        return -1;
    }

    /** True if registered interface {@code r} itself declares the signature at absolute Utf8 {@code nameU}/{@code descU}. */
    private static boolean ifaceDeclares(int r, Address nameU, Address descU)
    {
        int s = ifmSlotAbs(r, nameU, descU);
        return s >= 0 && ifDecl.get(ClassRegistry.clTab[r].ifmStart + s).EQ(ClassRegistry.clTab[r].base.toAddress());
    }

    /**
     * True if interface {@code sub} extends interface {@code sup}, directly or through its super-interfaces.
     * Breadth-first over the persistent {@code clIfaceReg} lists, bounded so a malformed registry cannot spin.
     */
    private static boolean ifaceExtends(int sub, int sup)
    {
        int[] work = new int[64];
        int n = 0;
        work[n] = sub;
        n += 1;
        int i = 0;
        while (i < n)
        {
            int r = work[i];
            int j = 0;
            while (j < clIfaceRegN[r])
            {
                int x = clIfaceReg[r * MAX_DIRECT_IF + j];
                if (x == sup)
                {
                    return true;
                }
                if (x >= 0 && n < work.length)
                {
                    work[n] = x;
                    n += 1;
                }
                j += 1;
            }
            i += 1;
        }
        return false;
    }

    /**
     * Of the interfaces {@code regs[0..n)}, the MAXIMALLY SPECIFIC one that declares the signature (JVMS
     * 5.4.3.3): a declaring interface that no other declaring interface in the set extends. -1 when none of
     * them declares it. Two unrelated maximally specific declarers keep the first, in the set's order.
     */
    static int mostSpecificDeclarer(int[] regs, int n, Address nameU, Address descU)
    {
        int best = -1;
        int i = 0;
        while (i < n)
        {
            int r = regs[i];
            if (r >= 0 && r < ClassRegistry.clCount && ClassRegistry.clTab[r] != null && ifaceDeclares(r, nameU, descU))
            {
                if (best < 0 || ifaceExtends(r, best))
                {
                    best = r;
                }
            }
            i += 1;
        }
        return best;
    }

    /** Per-interface itable slot for an InterfaceMethodref call site: the signature's position in
     *  the ref'd interface's flattened run -- the writer's numbering (0 if the interface isn't
     *  registered or lacks the signature: a denylist-fringe site whose entry is never taken). */
    static int ifSlotOf(int idx)
    {
        int r = ClassRegistry.classRegByName(Loader.refClassNameOff(idx));
        if (r < 0)
        {
            return 0;
        }
        int s = ifmSlotIn(r, Loader.gView.base.toAddress(), Loader.mrefNameOff(idx), Loader.mrefDescOff(idx));
        return s >= 0 ? s : 0;
    }

    /**
     * As {@link #ifmSlotIn}, but with the name and descriptor at two INDEPENDENT absolute Utf8 addresses --
     * which is how a dispatch site carries them ({@code vsName}/{@code vsDesc}), one per blob.
     */
    private static int ifmSlotAbs(int r, Address nameU, Address descU)
    {
        return ifmSlotFind(r, nameU, 0, descU, 0);
    }

    /** Slot of (name, desc in {@code base}) within registered interface {@code r}'s run, or -1. */
    static int ifmSlotIn(int r, Address base, int nameOff, int descOff)
    {
        return ifmSlotFind(r, base, nameOff, base, descOff);
    }

    /** Per-interface itable for interface {@code ir} on the CURRENT class: slot s = the class's
     *  impl of the interface's s-th flattened method (vtable match), else a DEFAULT declared by an
     *  interface in this class's own closure ({@code n} entries of {@link #ifClosureBuf} -- not the
     *  arbitrary declaring interface: two unrelated interfaces can share a signature), else 0
     *  (refilled after phase B compiles late defaults). */
    private static long buildItableFor(int ir, int n)
    {
        int count = ClassRegistry.clTab[ir].ifmCount;
        DataReference it = Heap.allocData(count > 0 ? count * 8 : 8);
        int s = 0;
        while (s < count)
        {
            int i = ClassRegistry.clTab[ir].ifmStart + s;
            long buf = 0L;
            int vs = VtableLayout.findVtSlotAt(ifBase.get(i), ifNameOff[i], ifDescOff[i]);
            if (vs >= 0)
            {
                buf = Loader.slotBuf(vs);
                if (buf == 0L)
                {
                    // The impl has a Code attribute but no buffer: RTA PRUNED it -- nothing statically
                    // reachable called it, so it was never pulled into the batch and never given a deferral
                    // stub, and this itable entry would stay 0. An interface call reaching it later then hits
                    // dispatchTargetGuard as a bare AIOOBE with no hint of what was missing -- which is
                    // exactly what `SharedSecrets.getJavaLangAccess().getEnumConstantsShared(...)` inside
                    // EnumMap does once its caller arrives through demand-loaded code.
                    //
                    // Minting here rather than in slotBuf is deliberate. Doing it for every vtable slot costs
                    // 3-4x the code arena per batch (measured: 8K->31K, 20K->64K, 23K->89K), and buys nothing
                    // for plain virtual dispatch: RTA marks all virtuals of an INSTANTIATED class, and a class
                    // that is never instantiated can never be a receiver. Interface entries are the case that
                    // actually goes empty, and they are a small fraction of the methods.
                    buf = Loader.mintPrunedStub(vs);
                }
            }
            else
            {
                buf = defaultBySig(n, ifBase.get(i), ifNameOff[i], ifDescOff[i]);
            }
            it.plus(s * 8).store(buf);
            s += 1;
        }
        return it.toLong();
    }

    /**
     * Registry index of the interface in the ref CLASS's transitive closure that declares Methodref
     * {@code idx}'s name+descriptor, or -1. Only consulted once {@link VtableLayout#exactVtableSlot} has missed, so the
     * closure walk costs nothing on the ordinary path.
     */
    private static int defaultIfaceRegOf(int idx)
    {
        int rc = ClassRegistry.classRegByName(Loader.refClassNameOff(idx));
        if (rc < 0 || ClassRegistry.clTab[rc] == null || ClassRegistry.clTab[rc].isIface)
        {
            return -1;
        }
        int n = ifaceClosureOf(rc);
        int i = 0;
        while (i < n)
        {
            int ir = ifClosureBuf[i];
            if (ifmSlotIn(ir, Loader.gView.base.toAddress(), Loader.mrefNameOff(idx), Loader.mrefDescOff(idx)) >= 0)
            {
                return ir;
            }
            i += 1;
        }
        return -1;
    }

    /** True if this {@code invokevirtual} must go through the itable -- see {@link Symbols#defaultDispatch}. */
    static boolean defaultDispatch(int idx)
    {
        return VtableLayout.exactVtableSlot(idx) < 0 && defaultIfaceRegOf(idx) >= 0;
    }

    /** Type node of the interface declaring the inherited default named by Methodref {@code idx}, or 0. */
    static long defaultIfaceTypeOf(int idx)
    {
        int ir = defaultIfaceRegOf(idx);
        return ir < 0 ? 0L : ClassRegistry.clTab[ir].type.toLong();
    }

    /** Slot of Methodref {@code idx} within that interface's flattened method list. */
    static int defaultIfaceSlotOf(int idx)
    {
        int ir = defaultIfaceRegOf(idx);
        return ir < 0 ? 0 : ifmSlotIn(ir, Loader.gView.base.toAddress(), Loader.mrefNameOff(idx), Loader.mrefDescOff(idx));
    }

    /** M8 itables: verify interface {@code reg}'s flattened per-interface slot numbering against
     *  the writer's (the vtSig entry carries its flattened interfaceMethods list) -- checked at
     *  registration, before any implementor builds an itable from the run. Uses the entry found by
     *  the branch's earlier {@link VtableLayout#findVtSig} call. */
    static void checkIfParity(int reg)
    {
        if (VtableLayout.gVtSigSlots == 0L)
        {
            return;                                     // not a baked interface: nothing to compare
        }
        if (Loader.LOAD_LOG)                                   // the header; a DIFF prints its own below, so a
        {                                               //   parity failure is never silenced by the flag
            Uart.write(Magic.bytes("  itparity "));
            Loader.printNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
        }
        if (VtableLayout.gVtSigCount != ClassRegistry.clTab[reg].ifmCount)
        {
            ifParityHeader();
            Uart.write(Magic.bytes(" DIFF count "));
            VM.printDec(VtableLayout.gVtSigCount);
            Uart.putc(0x2F);
            VM.printDec(ClassRegistry.clTab[reg].ifmCount);
            Uart.putc(0x0A);
            return;
        }
        int s = 0;
        while (s < VtableLayout.gVtSigCount)
        {
            Address p = Address.fromLong(VtableLayout.gVtSigSlots + (long) s * 16L);
            int i = ClassRegistry.clTab[reg].ifmStart + s;
            if (!Loader.utf8EqAt(ifBase.get(i), ifNameOff[i], p.loadAddress(), 0)
                    || !Loader.utf8EqAt(ifBase.get(i), ifDescOff[i], p.plus(8L).loadAddress(), 0))
            {
                ifParityHeader();
                Uart.write(Magic.bytes(" DIFF slot "));
                VM.printDec(s);
                Uart.putc(0x0A);
                return;
            }
            s += 1;
        }
        if (Loader.LOAD_LOG)
        {
            Uart.write(Magic.bytes(" OK "));
            VM.printDec(VtableLayout.gVtSigCount);
            Uart.putc(0x0A);
        }
    }

    /** Print the itparity header a DIFF needs when {@link Loader#LOAD_LOG} left it out. */
    private static void ifParityHeader()
    {
        if (!Loader.LOAD_LOG)
        {
            Uart.write(Magic.bytes("  itparity "));
            Loader.printNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
        }
    }

    /**
     * Repair default-method imap slots left 0 during phase B. {@link #buildImap} fills an unoverridden interface
     * method's slot with {@link #defaultImplOf} = the interface default's COMPILED buffer, but phase B is
     * superclass-first (not super-interface-first), so the interface holding the default may be emitted AFTER an
     * implementor -- leaving its rgBuf (hence the slot) 0 at buildImap time (e.g. Streams$RangeIntSpliterator's
     * slot for Spliterator.getExactSizeIfKnown -> copyInto blr'd a 0 slot). Now that every body is emitted, re-run
     * defaultImplOf for each still-0 slot. Safe: defaultImplOf is context-free (persistent ifBase/rgBuf), returns
     * 0 for abstract methods, and a filled slot for an interface a class doesn't implement is never dispatched
     * (its dir lacks that interface). Mirrors fillClassVtBuf for the vtable.
     */
    static void refillImaps()
    {
        int m = 0;
        while (m < instImapN)
        {
            if (instImapDone[m])                        // no holes last time -> a no-op now; see instImapDone
            {
                rfSkip += 1;
                m += 1;
                continue;
            }
            rfVisit += 1;
            Address dir = instImaps.get(m).toAddress();                    // the class's itable DIRECTORY (per-interface tables)
            int reg = instImapReg[m];
            if (reg < 0 || dir.isZero())
            {
                instImapDone[m] = true;                 // nothing to repair, ever
                m += 1;
                continue;
            }
            // ONE PASS OVER THE SLOTS, not three. This used to pre-scan with itableHasHole (to decide whether
            // to compute the closure), then scan again to refill, then scan a THIRD time to ask whether a hole
            // survived. The pre-scan bought almost nothing and the counters said so: `clos` was 1060 against
            // 1156 visits, i.e. 92% of visited imaps had a hole somewhere anyway, so the closure was being
            // computed regardless. refillItable already tests every slot for 0, so it can report what it saw.
            rfClos += 1;
            int n = ifaceClosureOf(reg);                // the class's full interface set (persistent registries)
            int slots = imapSlotCount(dir.toLong());
            int[] decl = instImapDecl[m];
            if (decl == null || instImapDeclN[m] != n || decl.length != slots)
            {
                decl = new int[slots];                  // the closure grew (or first visit): see instImapDecl
                int d = 0;
                while (d < slots)
                {
                    decl[d] = -2;
                    d += 1;
                }
                instImapDecl[m] = decl;
                instImapDeclN[m] = n;
            }
            boolean holes = false;
            int off = 0;
            int k = 0;
            DataReference t = dir.loadDataReference();
            while (!t.isZero())                             // walk entries by their TYPE key (order-independent)
            {
                int ir = ClassRegistry.regOfType(t.toAddress());
                if (ir >= 0)
                {
                    long it = dir.plus(k * 16).plus(8).loadLong();
                    if (refillItable(n, ir, it, decl, off))   // still short after the repair: a later batch may
                    {                                         //   compile the class that declares the default
                        holes = true;
                    }
                    off += ClassRegistry.clTab[ir].ifmCount;
                }
                k += 1;
                t = DataReference.fromAddress(dir.plus(k * 16).loadAddress());
            }
            if (holes)
            {
                rfHoleEnd += 1;
            }
            instImapDone[m] = !holes;
            m += 1;
        }
    }

    /**
     * Refill still-0 slots of interface {@code ir}'s itable {@code it} with late-compiled defaults, and report
     * whether any slot is STILL 0 afterwards -- which is what tells {@link #refillImaps} it cannot memoise
     * this imap yet, because a later batch may compile the class that declares the default.
     *
     * <p>Answering that here rather than re-scanning is the point: the caller used to walk these same slots
     * twice more (once to decide whether to call at all, once to ask the question this now answers), and the
     * loop already reads every slot.
     */
    private static boolean refillItable(int n, int ir, long it, int[] decl, int off)
    {
        boolean hole = false;
        int s = 0;
        while (s < ClassRegistry.clTab[ir].ifmCount)
        {
            rfFillSteps += 1;
            if (Address.fromLong(it).plus(s * 8L).loadLong() == 0L)
            {
                int i = ClassRegistry.clTab[ir].ifmStart + s;
                int d = decl[off + s];
                if (d == -2)
                {
                    d = mostSpecificDeclarer(ifClosureBuf, n, ifBase.get(i).plus(ifNameOff[i]), ifBase.get(i).plus(ifDescOff[i]));
                    decl[off + s] = d;
                }
                long b;
                if (d >= 0)
                {
                    MethodRegistry.buildRegIndex();                    // regBodyIn probes it; incremental, as in defaultBySig
                    b = Loader.regBodyIn(d, ifBase.get(i), ifNameOff[i], ifDescOff[i]);   // what defaultBySig returns here
                }
                else
                {
                    b = defaultBySig(n, ifBase.get(i), ifNameOff[i], ifDescOff[i]);   // no declarer: the full search
                }
                if (b != 0L)
                {
                    Address.fromLong(it).plus(s * 8L).store(b);
                }
                else
                {
                    hole = true;
                }
            }
            s += 1;
        }
        return hole;
    }

    /** Itable slots in directory {@code dir}: the sum of its registered interfaces' run lengths, in walk order. */
    private static int imapSlotCount(long dir)
    {
        int total = 0;
        int k = 0;
        DataReference t = Address.fromLong(dir).loadDataReference();
        while (!t.isZero())
        {
            int ir = ClassRegistry.regOfType(t.toAddress());
            if (ir >= 0)
            {
                total += ClassRegistry.clTab[ir].ifmCount;
            }
            k += 1;
            t = DataReference.fromAddress(Address.fromLong(dir).plus(k * 16).loadAddress());
        }
        return total;
    }

    /** {@link #ifaceClosure} but for an already-registered class {@code reg}, from the PERSISTENT registries
     *  ({@code clIfaceReg}/{@code clSuperReg}) rather than the transient current-compile state -- so refillImaps
     *  can recompute a class's interface set after phase B. Fills {@link #ifClosureBuf}; returns the count. */
    static int ifaceClosureOf(int reg)
    {
        int n = 0;
        int j = 0;
        while (j < clIfaceRegN[reg])                    // reg's own directly-declared interfaces
        {
            n = addIfaceUnique(n, clIfaceReg[reg * MAX_DIRECT_IF + j]);
            j += 1;
        }
        int sr = ClassRegistry.superRegOf(reg);                           // the whole superclass chain's direct interfaces
        int guard = 0;
        while (sr >= 0 && guard < 64)
        {
            j = 0;
            while (j < clIfaceRegN[sr])
            {
                n = addIfaceUnique(n, clIfaceReg[sr * MAX_DIRECT_IF + j]);
                j += 1;
            }
            sr = ClassRegistry.superRegOf(sr);
            guard += 1;
        }
        int i = 0;
        while (i < n)                                   // fold in each collected interface's extended interfaces
        {
            int r = ifClosureBuf[i];
            j = 0;
            while (j < clIfaceRegN[r])
            {
                n = addIfaceUnique(n, clIfaceReg[r * MAX_DIRECT_IF + j]);
                j += 1;
            }
            i += 1;
        }
        return n;
    }

    /** A concrete (default) impl of interface-method global-index {@code g} declared in one of the {@code n}
     *  interfaces in {@link #ifClosureBuf}, or 0. Unlike {@link #defaultImplOf} (which only checks the interface
     *  the method was FIRST registered in), this finds a default an implementing SUB-interface provides for a
     *  method declared abstract in a super-interface -- e.g. {@code Spliterator.OfInt}'s
     *  {@code tryAdvance(Consumer)} for the abstract {@code Spliterator.tryAdvance(Consumer)}. */
    private static long defaultBySig(int n, Address base, int nameOff, int descOff)
    {
        // THE REGISTRY IS PROBED BY NAME HASH, NOT SCANNED. This was a linear walk of all rgCount entries per
        // closure interface, and refillImaps calls it for EVERY PERMANENTLY-EMPTY itable slot on EVERY batch --
        // slots that can never be filled, because nothing in the closure declares a body for them (an abstract
        // method the class declares itself, or a native with no VM helper). Measured on the launcher it was
        // 97.9% of the whole imap refill (fillT=19,427ms of imap=19,852ms at batch 10) and grew with rgCount,
        // which is why per-batch cost climbed 1.3s -> 3.6s across a boot. Same defect and same remedy as the
        // demand-load arc's `pull` pass, where a per-item linear registry scan went 6,664ms -> 1,539ms on a
        // name hash index.
        //
        // THE CLOSURE ORDER IS PRESERVED EXACTLY: this returns the first match in CLOSURE order, so the chain
        // is walked once per closure interface rather than taking the first entry the bucket happens to yield.
        // Two interfaces in one closure may both declare the same name+descriptor, and picking the other one
        // would change which default body runs -- silently.
        MethodRegistry.buildRegIndex();
        rfDbsCalls += 1;
        // THE MAXIMALLY SPECIFIC DECLARER, NOT THE FIRST REGISTERED BODY. Taking the first closure interface
        // whose body happened to be registered answered by HISTORY: with Square extends Base(implements Shape)
        // implements Polygon(extends Shape, overrides kind), Shape.kind was registered (Base's itable needed
        // it) and Polygon.kind was not yet, so Square's itable got Shape.kind -- `shape` for a `polygon` --
        // whenever something had registered the less specific body first (VirtualInvokeProbe in a batch). The
        // declarer is decided from the interfaces' own declarations; if its body is not registered yet the slot
        // stays a hole, which refill and late dispatch resolve to that declarer, never to a less specific one.
        int decl = mostSpecificDeclarer(ifClosureBuf, n, base.plus(nameOff), base.plus(descOff));
        if (decl >= 0)
        {
            return Loader.regBodyIn(decl, base, nameOff, descOff);
        }
        int i = 0;
        while (i < n)
        {
            int ir = ifClosureBuf[i];
            DataReference ibase = ClassRegistry.clTab[ir].base;                   // a closure interface's blob; its own methods registered under it
            rfProbeSteps += 1;
            rfHashNameSteps += u2(base.plus(nameOff));
            // Hashed PER INTERFACE now that the index is keyed on class+name -- the interface supplies the
            // class half. CLOSURE ORDER IS STILL EXACT: one probe per closure interface, walked in order, so
            // the first match is the same entry the name-only chain would have yielded.
            //
            // THE CLASS HALF IS NOT RE-FOLDED. It is the PREFIX of the FNV key and depends only on the closure
            // interface, so it is cached at registration; this continues the fold over the method name and
            // lands in exactly the bucket utf8Hash2 would have. MEASURED before it was written, because the
            // four step counters above rank only slot READS and could not see this at all: over a demo-suite
            // boot the class-name bytes were 916k of the refill's 1,420k steps, the method-name bytes 415k,
            // and the slot reads 61k -- while `chain` read 0k, i.e. these probes reach an EMPTY bucket and
            // computing the key was the entire cost of a search that is expected to fail.
            int k = MethodRegistry.rgBucket[Utf8.utf8HashFrom(ClassRegistry.clTab[ir].nameHash, base, nameOff) & (MethodRegistry.RGTAB - 1)];
            while (k >= 0)
            {
                rfChainSteps += 1;
                if (MethodRegistry.rgTab[k].base.EQ(ibase) && MethodRegistry.rgTab[k].buf != 0L
                        && Loader.utf8EqAt(base, nameOff, MethodRegistry.rgTab[k].base.toAddress(), MethodRegistry.rgTab[k].nameOff)
                        && Loader.utf8EqAt(base, descOff, MethodRegistry.rgTab[k].base.toAddress(), MethodRegistry.rgTab[k].descOff))
                {
                    return MethodRegistry.rgTab[k].buf;
                }
                k = MethodRegistry.rgNext[k];
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * itable directory (ObjectModel): one {@code {interfaceType, itable}} entry per
     * implemented interface — TRANSITIVELY, so a super-interface reached only through
     * another interface (or the superclass) is keyed too. Every entry shares this class's
     * flat imap as its itable — the metal indexes the imap by the interface method's
     * <em>global</em> slot ({@link #ifSlotOf}), so one table serves all interfaces. The
     * directory adds the interfaceType-keyed lookup the core's {@code invokeinterface}
     * searches for; without the transitive entries, a call site typed to a super-interface
     * (e.g. {@code Iterable.iterator} on an {@code ArrayList implements List extends Iterable})
     * would miss and the search would run past the sentinel.
     */
    static long buildItableDir()
    {
        int n = ifaceClosure();
        if (n == 0)
        {
            return 0L;
        }
        DataReference dir = Heap.allocData((n + 1) * 16);             // {interfaceType@0, itable@8} + sentinel
        int k = 0;
        while (k < n)
        {
            dir.plus(k * 16).plus(0).store(ClassRegistry.clTab[ifClosureBuf[k]].type);   // interfaceType
            dir.plus(k * 16).plus(8).store(buildItableFor(ifClosureBuf[k], n));   // PER-interface itable
            k += 1;
        }
        dir.plus(k * 16).plus(0).store(0L);             // sentinel: interfaceType 0 ends the directory
        dir.plus(k * 16).plus(8).store(0L);
        return dir.toLong();
    }

    /**
     * The transitive set of interfaces the current class implements, as registry indices in
     * {@link #ifClosureBuf} (return = count). Seeds with this class's directly-declared interfaces plus the
     * superclass's, then repeatedly folds in each collected interface's own extended interfaces
     * ({@link #clIfaceReg}) until closed. De-duped.
     */
    private static int ifaceClosure()
    {
        int n = 0;
        int k = 0;
        while (k < Loader.gView.implIfName.length)                         // this class's directly-declared interfaces
        {
            int r = ClassRegistry.classRegByName(Loader.gView.implIfName[k]);
            if (r >= 0)
            {
                n = addIfaceUnique(n, r);
            }
            k += 1;
        }
        int sr = ClassRegistry.classRegByName(Loader.gView.superNameOff);          // interfaces inherited from the ENTIRE superclass chain --
        int guard = 0;                                   // an interface implemented several levels up (e.g. IntPipeline$1
        while (sr >= 0 && guard < 64)                    // -> StatelessOp -> ReferencePipeline implements Stream) must
        {                                                // still be in this class's dir, else invokeinterface misses it
            int j = 0;                                   // (walking only the immediate super dropped Stream 2 levels up).
            while (j < clIfaceRegN[sr])
            {
                n = addIfaceUnique(n, clIfaceReg[sr * MAX_DIRECT_IF + j]);
                j += 1;
            }
            sr = ClassRegistry.superRegOf(sr);
            guard += 1;
        }
        int i = 0;
        while (i < n)                                    // fold in each collected interface's extended interfaces
        {
            int r = ifClosureBuf[i];
            int j = 0;
            while (j < clIfaceRegN[r])
            {
                n = addIfaceUnique(n, clIfaceReg[r * MAX_DIRECT_IF + j]);
                j += 1;
            }
            i += 1;
        }
        return n;
    }

    /** Append registry index {@code r} to {@link #ifClosureBuf} if absent; return the new count. */
    private static int addIfaceUnique(int n, int r)
    {
        rfClosSteps += 1;
        int i = 0;
        while (i < n)
        {
            if (ifClosureBuf[i] == r)
            {
                return n;
            }
            i += 1;
        }
        if (n < ifClosureBuf.length)
        {
            ifClosureBuf[n] = r;
            n += 1;
        }
        return n;
    }

    /** Wrap a built lambda thunk into a class: imap (SAM slot -> thunk), itable dir, Type, TIB; return the TIB. */
    /** True if registry index {@code r} is already among the first {@code n} entries of {@code ifClosureBuf}. */
    static boolean inIfaceClosure(int r, int n)
    {
        int i = 0;
        while (i < n)
        {
            if (ifClosureBuf[i] == r)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** Type node of the interface owning InterfaceMethodref {@code idx}. */
    static long ifaceTypeOfMethod(int idx)
    {
        int classIdx = u2(Loader.gView.base.plus(Loader.gView.cp[idx]));            // *ref.class_index -> Class entry
        return DynamicTypeCheck.typeOfClass(classIdx).toLong();
    }
}
