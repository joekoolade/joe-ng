/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-16
 */
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FIRST calls racing across cores: four threads, released together, each call N distinct small methods -- every one
 * a lazy compile on its first call -- in a different order per thread, so one core's compile-and-install overlaps
 * another core's first execution of the same body, and the four contend for the loader lock. Each body does a
 * virtual call on a shared AtomicInteger, the shape of ChmRaceProbe's computeIfAbsent mapping lambda.
 *
 * <p>The STRESS form, 480 methods. It measured the SMP switch race fixed in {@code VMScheduler.markLeaving} -- a task
 * claimable while the core leaving it was still on its stack, so two cores ran on one stack for a few instructions:
 * a wild branch to pc 0x1 with x30=0, a branch into a data arena, or a NULL local in Baseline.compileBody. Unfixed,
 * 6 failures in 70 runs (3 in 40 in a same-session control); fixed, 0 in 40. A class this size makes each
 * first-call compile hold the loader lock for seconds, so the lock watchdog prints slow-hold lines: CLASSIFY a run by
 * its answer and by the absence of TRAP / BOOT RE-ENTERED / an exception, never by a diff. Not in the regression
 * set for that reason. Generated; prints values only.
 */
public class FirstCallRaceStress
{
    static final int N = 480;

    static int m0(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 0;
    }

    static int m1(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 1;
    }

    static int m2(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 2;
    }

    static int m3(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 3;
    }

    static int m4(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 4;
    }

    static int m5(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 5;
    }

    static int m6(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 6;
    }

    static int m7(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 7;
    }

    static int m8(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 8;
    }

    static int m9(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 9;
    }

    static int m10(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 10;
    }

    static int m11(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 11;
    }

    static int m12(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 12;
    }

    static int m13(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 13;
    }

    static int m14(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 14;
    }

    static int m15(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 15;
    }

    static int m16(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 16;
    }

    static int m17(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 17;
    }

    static int m18(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 18;
    }

    static int m19(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 19;
    }

    static int m20(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 20;
    }

    static int m21(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 21;
    }

    static int m22(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 22;
    }

    static int m23(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 23;
    }

    static int m24(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 24;
    }

    static int m25(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 25;
    }

    static int m26(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 26;
    }

    static int m27(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 27;
    }

    static int m28(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 28;
    }

    static int m29(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 29;
    }

    static int m30(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 30;
    }

    static int m31(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 31;
    }

    static int m32(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 32;
    }

    static int m33(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 33;
    }

    static int m34(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 34;
    }

    static int m35(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 35;
    }

    static int m36(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 36;
    }

    static int m37(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 37;
    }

    static int m38(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 38;
    }

    static int m39(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 39;
    }

    static int m40(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 40;
    }

    static int m41(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 41;
    }

    static int m42(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 42;
    }

    static int m43(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 43;
    }

    static int m44(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 44;
    }

    static int m45(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 45;
    }

    static int m46(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 46;
    }

    static int m47(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 47;
    }

    static int m48(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 48;
    }

    static int m49(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 49;
    }

    static int m50(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 50;
    }

    static int m51(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 51;
    }

    static int m52(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 52;
    }

    static int m53(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 53;
    }

    static int m54(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 54;
    }

    static int m55(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 55;
    }

    static int m56(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 56;
    }

    static int m57(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 57;
    }

    static int m58(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 58;
    }

    static int m59(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 59;
    }

    static int m60(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 60;
    }

    static int m61(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 61;
    }

    static int m62(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 62;
    }

    static int m63(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 63;
    }

    static int m64(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 64;
    }

    static int m65(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 65;
    }

    static int m66(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 66;
    }

    static int m67(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 67;
    }

    static int m68(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 68;
    }

    static int m69(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 69;
    }

    static int m70(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 70;
    }

    static int m71(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 71;
    }

    static int m72(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 72;
    }

    static int m73(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 73;
    }

    static int m74(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 74;
    }

    static int m75(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 75;
    }

    static int m76(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 76;
    }

    static int m77(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 77;
    }

    static int m78(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 78;
    }

    static int m79(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 79;
    }

    static int m80(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 80;
    }

    static int m81(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 81;
    }

    static int m82(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 82;
    }

    static int m83(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 83;
    }

    static int m84(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 84;
    }

    static int m85(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 85;
    }

    static int m86(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 86;
    }

    static int m87(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 87;
    }

    static int m88(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 88;
    }

    static int m89(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 89;
    }

    static int m90(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 90;
    }

    static int m91(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 91;
    }

    static int m92(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 92;
    }

    static int m93(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 93;
    }

    static int m94(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 94;
    }

    static int m95(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 95;
    }

    static int m96(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 96;
    }

    static int m97(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 97;
    }

    static int m98(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 98;
    }

    static int m99(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 99;
    }

    static int m100(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 100;
    }

    static int m101(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 101;
    }

    static int m102(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 102;
    }

    static int m103(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 103;
    }

    static int m104(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 104;
    }

    static int m105(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 105;
    }

    static int m106(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 106;
    }

    static int m107(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 107;
    }

    static int m108(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 108;
    }

    static int m109(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 109;
    }

    static int m110(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 110;
    }

    static int m111(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 111;
    }

    static int m112(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 112;
    }

    static int m113(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 113;
    }

    static int m114(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 114;
    }

    static int m115(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 115;
    }

    static int m116(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 116;
    }

    static int m117(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 117;
    }

    static int m118(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 118;
    }

    static int m119(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 119;
    }

    static int m120(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 120;
    }

    static int m121(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 121;
    }

    static int m122(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 122;
    }

    static int m123(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 123;
    }

    static int m124(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 124;
    }

    static int m125(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 125;
    }

    static int m126(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 126;
    }

    static int m127(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 127;
    }

    static int m128(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 128;
    }

    static int m129(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 129;
    }

    static int m130(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 130;
    }

    static int m131(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 131;
    }

    static int m132(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 132;
    }

    static int m133(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 133;
    }

    static int m134(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 134;
    }

    static int m135(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 135;
    }

    static int m136(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 136;
    }

    static int m137(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 137;
    }

    static int m138(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 138;
    }

    static int m139(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 139;
    }

    static int m140(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 140;
    }

    static int m141(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 141;
    }

    static int m142(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 142;
    }

    static int m143(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 143;
    }

    static int m144(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 144;
    }

    static int m145(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 145;
    }

    static int m146(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 146;
    }

    static int m147(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 147;
    }

    static int m148(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 148;
    }

    static int m149(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 149;
    }

    static int m150(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 150;
    }

    static int m151(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 151;
    }

    static int m152(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 152;
    }

    static int m153(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 153;
    }

    static int m154(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 154;
    }

    static int m155(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 155;
    }

    static int m156(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 156;
    }

    static int m157(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 157;
    }

    static int m158(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 158;
    }

    static int m159(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 159;
    }

    static int m160(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 160;
    }

    static int m161(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 161;
    }

    static int m162(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 162;
    }

    static int m163(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 163;
    }

    static int m164(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 164;
    }

    static int m165(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 165;
    }

    static int m166(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 166;
    }

    static int m167(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 167;
    }

    static int m168(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 168;
    }

    static int m169(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 169;
    }

    static int m170(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 170;
    }

    static int m171(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 171;
    }

    static int m172(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 172;
    }

    static int m173(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 173;
    }

    static int m174(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 174;
    }

    static int m175(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 175;
    }

    static int m176(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 176;
    }

    static int m177(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 177;
    }

    static int m178(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 178;
    }

    static int m179(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 179;
    }

    static int m180(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 180;
    }

    static int m181(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 181;
    }

    static int m182(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 182;
    }

    static int m183(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 183;
    }

    static int m184(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 184;
    }

    static int m185(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 185;
    }

    static int m186(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 186;
    }

    static int m187(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 187;
    }

    static int m188(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 188;
    }

    static int m189(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 189;
    }

    static int m190(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 190;
    }

    static int m191(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 191;
    }

    static int m192(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 192;
    }

    static int m193(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 193;
    }

    static int m194(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 194;
    }

    static int m195(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 195;
    }

    static int m196(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 196;
    }

    static int m197(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 197;
    }

    static int m198(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 198;
    }

    static int m199(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 199;
    }

    static int m200(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 200;
    }

    static int m201(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 201;
    }

    static int m202(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 202;
    }

    static int m203(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 203;
    }

    static int m204(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 204;
    }

    static int m205(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 205;
    }

    static int m206(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 206;
    }

    static int m207(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 207;
    }

    static int m208(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 208;
    }

    static int m209(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 209;
    }

    static int m210(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 210;
    }

    static int m211(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 211;
    }

    static int m212(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 212;
    }

    static int m213(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 213;
    }

    static int m214(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 214;
    }

    static int m215(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 215;
    }

    static int m216(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 216;
    }

    static int m217(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 217;
    }

    static int m218(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 218;
    }

    static int m219(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 219;
    }

    static int m220(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 220;
    }

    static int m221(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 221;
    }

    static int m222(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 222;
    }

    static int m223(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 223;
    }

    static int m224(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 224;
    }

    static int m225(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 225;
    }

    static int m226(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 226;
    }

    static int m227(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 227;
    }

    static int m228(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 228;
    }

    static int m229(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 229;
    }

    static int m230(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 230;
    }

    static int m231(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 231;
    }

    static int m232(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 232;
    }

    static int m233(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 233;
    }

    static int m234(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 234;
    }

    static int m235(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 235;
    }

    static int m236(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 236;
    }

    static int m237(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 237;
    }

    static int m238(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 238;
    }

    static int m239(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 239;
    }

    static int m240(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 240;
    }

    static int m241(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 241;
    }

    static int m242(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 242;
    }

    static int m243(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 243;
    }

    static int m244(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 244;
    }

    static int m245(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 245;
    }

    static int m246(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 246;
    }

    static int m247(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 247;
    }

    static int m248(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 248;
    }

    static int m249(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 249;
    }

    static int m250(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 250;
    }

    static int m251(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 251;
    }

    static int m252(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 252;
    }

    static int m253(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 253;
    }

    static int m254(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 254;
    }

    static int m255(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 255;
    }

    static int m256(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 256;
    }

    static int m257(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 257;
    }

    static int m258(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 258;
    }

    static int m259(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 259;
    }

    static int m260(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 260;
    }

    static int m261(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 261;
    }

    static int m262(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 262;
    }

    static int m263(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 263;
    }

    static int m264(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 264;
    }

    static int m265(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 265;
    }

    static int m266(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 266;
    }

    static int m267(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 267;
    }

    static int m268(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 268;
    }

    static int m269(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 269;
    }

    static int m270(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 270;
    }

    static int m271(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 271;
    }

    static int m272(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 272;
    }

    static int m273(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 273;
    }

    static int m274(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 274;
    }

    static int m275(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 275;
    }

    static int m276(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 276;
    }

    static int m277(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 277;
    }

    static int m278(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 278;
    }

    static int m279(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 279;
    }

    static int m280(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 280;
    }

    static int m281(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 281;
    }

    static int m282(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 282;
    }

    static int m283(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 283;
    }

    static int m284(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 284;
    }

    static int m285(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 285;
    }

    static int m286(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 286;
    }

    static int m287(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 287;
    }

    static int m288(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 288;
    }

    static int m289(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 289;
    }

    static int m290(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 290;
    }

    static int m291(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 291;
    }

    static int m292(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 292;
    }

    static int m293(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 293;
    }

    static int m294(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 294;
    }

    static int m295(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 295;
    }

    static int m296(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 296;
    }

    static int m297(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 297;
    }

    static int m298(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 298;
    }

    static int m299(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 299;
    }

    static int m300(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 300;
    }

    static int m301(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 301;
    }

    static int m302(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 302;
    }

    static int m303(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 303;
    }

    static int m304(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 304;
    }

    static int m305(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 305;
    }

    static int m306(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 306;
    }

    static int m307(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 307;
    }

    static int m308(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 308;
    }

    static int m309(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 309;
    }

    static int m310(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 310;
    }

    static int m311(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 311;
    }

    static int m312(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 312;
    }

    static int m313(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 313;
    }

    static int m314(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 314;
    }

    static int m315(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 315;
    }

    static int m316(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 316;
    }

    static int m317(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 317;
    }

    static int m318(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 318;
    }

    static int m319(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 319;
    }

    static int m320(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 320;
    }

    static int m321(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 321;
    }

    static int m322(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 322;
    }

    static int m323(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 323;
    }

    static int m324(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 324;
    }

    static int m325(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 325;
    }

    static int m326(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 326;
    }

    static int m327(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 327;
    }

    static int m328(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 328;
    }

    static int m329(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 329;
    }

    static int m330(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 330;
    }

    static int m331(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 331;
    }

    static int m332(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 332;
    }

    static int m333(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 333;
    }

    static int m334(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 334;
    }

    static int m335(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 335;
    }

    static int m336(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 336;
    }

    static int m337(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 337;
    }

    static int m338(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 338;
    }

    static int m339(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 339;
    }

    static int m340(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 340;
    }

    static int m341(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 341;
    }

    static int m342(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 342;
    }

    static int m343(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 343;
    }

    static int m344(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 344;
    }

    static int m345(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 345;
    }

    static int m346(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 346;
    }

    static int m347(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 347;
    }

    static int m348(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 348;
    }

    static int m349(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 349;
    }

    static int m350(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 350;
    }

    static int m351(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 351;
    }

    static int m352(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 352;
    }

    static int m353(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 353;
    }

    static int m354(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 354;
    }

    static int m355(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 355;
    }

    static int m356(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 356;
    }

    static int m357(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 357;
    }

    static int m358(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 358;
    }

    static int m359(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 359;
    }

    static int m360(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 360;
    }

    static int m361(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 361;
    }

    static int m362(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 362;
    }

    static int m363(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 363;
    }

    static int m364(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 364;
    }

    static int m365(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 365;
    }

    static int m366(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 366;
    }

    static int m367(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 367;
    }

    static int m368(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 368;
    }

    static int m369(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 369;
    }

    static int m370(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 370;
    }

    static int m371(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 371;
    }

    static int m372(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 372;
    }

    static int m373(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 373;
    }

    static int m374(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 374;
    }

    static int m375(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 375;
    }

    static int m376(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 376;
    }

    static int m377(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 377;
    }

    static int m378(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 378;
    }

    static int m379(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 379;
    }

    static int m380(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 380;
    }

    static int m381(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 381;
    }

    static int m382(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 382;
    }

    static int m383(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 383;
    }

    static int m384(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 384;
    }

    static int m385(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 385;
    }

    static int m386(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 386;
    }

    static int m387(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 387;
    }

    static int m388(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 388;
    }

    static int m389(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 389;
    }

    static int m390(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 390;
    }

    static int m391(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 391;
    }

    static int m392(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 392;
    }

    static int m393(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 393;
    }

    static int m394(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 394;
    }

    static int m395(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 395;
    }

    static int m396(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 396;
    }

    static int m397(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 397;
    }

    static int m398(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 398;
    }

    static int m399(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 399;
    }

    static int m400(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 400;
    }

    static int m401(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 401;
    }

    static int m402(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 402;
    }

    static int m403(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 403;
    }

    static int m404(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 404;
    }

    static int m405(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 405;
    }

    static int m406(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 406;
    }

    static int m407(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 407;
    }

    static int m408(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 408;
    }

    static int m409(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 409;
    }

    static int m410(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 410;
    }

    static int m411(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 411;
    }

    static int m412(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 412;
    }

    static int m413(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 413;
    }

    static int m414(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 414;
    }

    static int m415(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 415;
    }

    static int m416(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 416;
    }

    static int m417(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 417;
    }

    static int m418(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 418;
    }

    static int m419(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 419;
    }

    static int m420(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 420;
    }

    static int m421(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 421;
    }

    static int m422(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 422;
    }

    static int m423(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 423;
    }

    static int m424(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 424;
    }

    static int m425(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 425;
    }

    static int m426(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 426;
    }

    static int m427(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 427;
    }

    static int m428(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 428;
    }

    static int m429(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 429;
    }

    static int m430(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 430;
    }

    static int m431(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 431;
    }

    static int m432(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 432;
    }

    static int m433(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 433;
    }

    static int m434(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 434;
    }

    static int m435(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 435;
    }

    static int m436(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 436;
    }

    static int m437(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 437;
    }

    static int m438(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 438;
    }

    static int m439(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 439;
    }

    static int m440(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 440;
    }

    static int m441(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 441;
    }

    static int m442(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 442;
    }

    static int m443(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 443;
    }

    static int m444(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 444;
    }

    static int m445(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 445;
    }

    static int m446(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 446;
    }

    static int m447(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 447;
    }

    static int m448(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 448;
    }

    static int m449(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 449;
    }

    static int m450(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 450;
    }

    static int m451(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 451;
    }

    static int m452(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 452;
    }

    static int m453(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 453;
    }

    static int m454(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 454;
    }

    static int m455(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 455;
    }

    static int m456(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 456;
    }

    static int m457(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 457;
    }

    static int m458(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 458;
    }

    static int m459(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 459;
    }

    static int m460(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 460;
    }

    static int m461(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 461;
    }

    static int m462(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 462;
    }

    static int m463(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 463;
    }

    static int m464(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 464;
    }

    static int m465(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 465;
    }

    static int m466(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 466;
    }

    static int m467(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 467;
    }

    static int m468(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 468;
    }

    static int m469(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 469;
    }

    static int m470(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 470;
    }

    static int m471(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 471;
    }

    static int m472(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 472;
    }

    static int m473(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 6 + 473;
    }

    static int m474(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 7 + 474;
    }

    static int m475(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 8 + 475;
    }

    static int m476(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 2 + 476;
    }

    static int m477(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 3 + 477;
    }

    static int m478(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 4 + 478;
    }

    static int m479(AtomicInteger c, int k)
    {
        c.incrementAndGet();
        return k * 5 + 479;
    }

    static int call(int i, AtomicInteger c, int k)
    {
        switch (i)
        {
            case 0:
                return m0(c, k);
            case 1:
                return m1(c, k);
            case 2:
                return m2(c, k);
            case 3:
                return m3(c, k);
            case 4:
                return m4(c, k);
            case 5:
                return m5(c, k);
            case 6:
                return m6(c, k);
            case 7:
                return m7(c, k);
            case 8:
                return m8(c, k);
            case 9:
                return m9(c, k);
            case 10:
                return m10(c, k);
            case 11:
                return m11(c, k);
            case 12:
                return m12(c, k);
            case 13:
                return m13(c, k);
            case 14:
                return m14(c, k);
            case 15:
                return m15(c, k);
            case 16:
                return m16(c, k);
            case 17:
                return m17(c, k);
            case 18:
                return m18(c, k);
            case 19:
                return m19(c, k);
            case 20:
                return m20(c, k);
            case 21:
                return m21(c, k);
            case 22:
                return m22(c, k);
            case 23:
                return m23(c, k);
            case 24:
                return m24(c, k);
            case 25:
                return m25(c, k);
            case 26:
                return m26(c, k);
            case 27:
                return m27(c, k);
            case 28:
                return m28(c, k);
            case 29:
                return m29(c, k);
            case 30:
                return m30(c, k);
            case 31:
                return m31(c, k);
            case 32:
                return m32(c, k);
            case 33:
                return m33(c, k);
            case 34:
                return m34(c, k);
            case 35:
                return m35(c, k);
            case 36:
                return m36(c, k);
            case 37:
                return m37(c, k);
            case 38:
                return m38(c, k);
            case 39:
                return m39(c, k);
            case 40:
                return m40(c, k);
            case 41:
                return m41(c, k);
            case 42:
                return m42(c, k);
            case 43:
                return m43(c, k);
            case 44:
                return m44(c, k);
            case 45:
                return m45(c, k);
            case 46:
                return m46(c, k);
            case 47:
                return m47(c, k);
            case 48:
                return m48(c, k);
            case 49:
                return m49(c, k);
            case 50:
                return m50(c, k);
            case 51:
                return m51(c, k);
            case 52:
                return m52(c, k);
            case 53:
                return m53(c, k);
            case 54:
                return m54(c, k);
            case 55:
                return m55(c, k);
            case 56:
                return m56(c, k);
            case 57:
                return m57(c, k);
            case 58:
                return m58(c, k);
            case 59:
                return m59(c, k);
            case 60:
                return m60(c, k);
            case 61:
                return m61(c, k);
            case 62:
                return m62(c, k);
            case 63:
                return m63(c, k);
            case 64:
                return m64(c, k);
            case 65:
                return m65(c, k);
            case 66:
                return m66(c, k);
            case 67:
                return m67(c, k);
            case 68:
                return m68(c, k);
            case 69:
                return m69(c, k);
            case 70:
                return m70(c, k);
            case 71:
                return m71(c, k);
            case 72:
                return m72(c, k);
            case 73:
                return m73(c, k);
            case 74:
                return m74(c, k);
            case 75:
                return m75(c, k);
            case 76:
                return m76(c, k);
            case 77:
                return m77(c, k);
            case 78:
                return m78(c, k);
            case 79:
                return m79(c, k);
            case 80:
                return m80(c, k);
            case 81:
                return m81(c, k);
            case 82:
                return m82(c, k);
            case 83:
                return m83(c, k);
            case 84:
                return m84(c, k);
            case 85:
                return m85(c, k);
            case 86:
                return m86(c, k);
            case 87:
                return m87(c, k);
            case 88:
                return m88(c, k);
            case 89:
                return m89(c, k);
            case 90:
                return m90(c, k);
            case 91:
                return m91(c, k);
            case 92:
                return m92(c, k);
            case 93:
                return m93(c, k);
            case 94:
                return m94(c, k);
            case 95:
                return m95(c, k);
            case 96:
                return m96(c, k);
            case 97:
                return m97(c, k);
            case 98:
                return m98(c, k);
            case 99:
                return m99(c, k);
            case 100:
                return m100(c, k);
            case 101:
                return m101(c, k);
            case 102:
                return m102(c, k);
            case 103:
                return m103(c, k);
            case 104:
                return m104(c, k);
            case 105:
                return m105(c, k);
            case 106:
                return m106(c, k);
            case 107:
                return m107(c, k);
            case 108:
                return m108(c, k);
            case 109:
                return m109(c, k);
            case 110:
                return m110(c, k);
            case 111:
                return m111(c, k);
            case 112:
                return m112(c, k);
            case 113:
                return m113(c, k);
            case 114:
                return m114(c, k);
            case 115:
                return m115(c, k);
            case 116:
                return m116(c, k);
            case 117:
                return m117(c, k);
            case 118:
                return m118(c, k);
            case 119:
                return m119(c, k);
            case 120:
                return m120(c, k);
            case 121:
                return m121(c, k);
            case 122:
                return m122(c, k);
            case 123:
                return m123(c, k);
            case 124:
                return m124(c, k);
            case 125:
                return m125(c, k);
            case 126:
                return m126(c, k);
            case 127:
                return m127(c, k);
            case 128:
                return m128(c, k);
            case 129:
                return m129(c, k);
            case 130:
                return m130(c, k);
            case 131:
                return m131(c, k);
            case 132:
                return m132(c, k);
            case 133:
                return m133(c, k);
            case 134:
                return m134(c, k);
            case 135:
                return m135(c, k);
            case 136:
                return m136(c, k);
            case 137:
                return m137(c, k);
            case 138:
                return m138(c, k);
            case 139:
                return m139(c, k);
            case 140:
                return m140(c, k);
            case 141:
                return m141(c, k);
            case 142:
                return m142(c, k);
            case 143:
                return m143(c, k);
            case 144:
                return m144(c, k);
            case 145:
                return m145(c, k);
            case 146:
                return m146(c, k);
            case 147:
                return m147(c, k);
            case 148:
                return m148(c, k);
            case 149:
                return m149(c, k);
            case 150:
                return m150(c, k);
            case 151:
                return m151(c, k);
            case 152:
                return m152(c, k);
            case 153:
                return m153(c, k);
            case 154:
                return m154(c, k);
            case 155:
                return m155(c, k);
            case 156:
                return m156(c, k);
            case 157:
                return m157(c, k);
            case 158:
                return m158(c, k);
            case 159:
                return m159(c, k);
            case 160:
                return m160(c, k);
            case 161:
                return m161(c, k);
            case 162:
                return m162(c, k);
            case 163:
                return m163(c, k);
            case 164:
                return m164(c, k);
            case 165:
                return m165(c, k);
            case 166:
                return m166(c, k);
            case 167:
                return m167(c, k);
            case 168:
                return m168(c, k);
            case 169:
                return m169(c, k);
            case 170:
                return m170(c, k);
            case 171:
                return m171(c, k);
            case 172:
                return m172(c, k);
            case 173:
                return m173(c, k);
            case 174:
                return m174(c, k);
            case 175:
                return m175(c, k);
            case 176:
                return m176(c, k);
            case 177:
                return m177(c, k);
            case 178:
                return m178(c, k);
            case 179:
                return m179(c, k);
            case 180:
                return m180(c, k);
            case 181:
                return m181(c, k);
            case 182:
                return m182(c, k);
            case 183:
                return m183(c, k);
            case 184:
                return m184(c, k);
            case 185:
                return m185(c, k);
            case 186:
                return m186(c, k);
            case 187:
                return m187(c, k);
            case 188:
                return m188(c, k);
            case 189:
                return m189(c, k);
            case 190:
                return m190(c, k);
            case 191:
                return m191(c, k);
            case 192:
                return m192(c, k);
            case 193:
                return m193(c, k);
            case 194:
                return m194(c, k);
            case 195:
                return m195(c, k);
            case 196:
                return m196(c, k);
            case 197:
                return m197(c, k);
            case 198:
                return m198(c, k);
            case 199:
                return m199(c, k);
            case 200:
                return m200(c, k);
            case 201:
                return m201(c, k);
            case 202:
                return m202(c, k);
            case 203:
                return m203(c, k);
            case 204:
                return m204(c, k);
            case 205:
                return m205(c, k);
            case 206:
                return m206(c, k);
            case 207:
                return m207(c, k);
            case 208:
                return m208(c, k);
            case 209:
                return m209(c, k);
            case 210:
                return m210(c, k);
            case 211:
                return m211(c, k);
            case 212:
                return m212(c, k);
            case 213:
                return m213(c, k);
            case 214:
                return m214(c, k);
            case 215:
                return m215(c, k);
            case 216:
                return m216(c, k);
            case 217:
                return m217(c, k);
            case 218:
                return m218(c, k);
            case 219:
                return m219(c, k);
            case 220:
                return m220(c, k);
            case 221:
                return m221(c, k);
            case 222:
                return m222(c, k);
            case 223:
                return m223(c, k);
            case 224:
                return m224(c, k);
            case 225:
                return m225(c, k);
            case 226:
                return m226(c, k);
            case 227:
                return m227(c, k);
            case 228:
                return m228(c, k);
            case 229:
                return m229(c, k);
            case 230:
                return m230(c, k);
            case 231:
                return m231(c, k);
            case 232:
                return m232(c, k);
            case 233:
                return m233(c, k);
            case 234:
                return m234(c, k);
            case 235:
                return m235(c, k);
            case 236:
                return m236(c, k);
            case 237:
                return m237(c, k);
            case 238:
                return m238(c, k);
            case 239:
                return m239(c, k);
            case 240:
                return m240(c, k);
            case 241:
                return m241(c, k);
            case 242:
                return m242(c, k);
            case 243:
                return m243(c, k);
            case 244:
                return m244(c, k);
            case 245:
                return m245(c, k);
            case 246:
                return m246(c, k);
            case 247:
                return m247(c, k);
            case 248:
                return m248(c, k);
            case 249:
                return m249(c, k);
            case 250:
                return m250(c, k);
            case 251:
                return m251(c, k);
            case 252:
                return m252(c, k);
            case 253:
                return m253(c, k);
            case 254:
                return m254(c, k);
            case 255:
                return m255(c, k);
            case 256:
                return m256(c, k);
            case 257:
                return m257(c, k);
            case 258:
                return m258(c, k);
            case 259:
                return m259(c, k);
            case 260:
                return m260(c, k);
            case 261:
                return m261(c, k);
            case 262:
                return m262(c, k);
            case 263:
                return m263(c, k);
            case 264:
                return m264(c, k);
            case 265:
                return m265(c, k);
            case 266:
                return m266(c, k);
            case 267:
                return m267(c, k);
            case 268:
                return m268(c, k);
            case 269:
                return m269(c, k);
            case 270:
                return m270(c, k);
            case 271:
                return m271(c, k);
            case 272:
                return m272(c, k);
            case 273:
                return m273(c, k);
            case 274:
                return m274(c, k);
            case 275:
                return m275(c, k);
            case 276:
                return m276(c, k);
            case 277:
                return m277(c, k);
            case 278:
                return m278(c, k);
            case 279:
                return m279(c, k);
            case 280:
                return m280(c, k);
            case 281:
                return m281(c, k);
            case 282:
                return m282(c, k);
            case 283:
                return m283(c, k);
            case 284:
                return m284(c, k);
            case 285:
                return m285(c, k);
            case 286:
                return m286(c, k);
            case 287:
                return m287(c, k);
            case 288:
                return m288(c, k);
            case 289:
                return m289(c, k);
            case 290:
                return m290(c, k);
            case 291:
                return m291(c, k);
            case 292:
                return m292(c, k);
            case 293:
                return m293(c, k);
            case 294:
                return m294(c, k);
            case 295:
                return m295(c, k);
            case 296:
                return m296(c, k);
            case 297:
                return m297(c, k);
            case 298:
                return m298(c, k);
            case 299:
                return m299(c, k);
            case 300:
                return m300(c, k);
            case 301:
                return m301(c, k);
            case 302:
                return m302(c, k);
            case 303:
                return m303(c, k);
            case 304:
                return m304(c, k);
            case 305:
                return m305(c, k);
            case 306:
                return m306(c, k);
            case 307:
                return m307(c, k);
            case 308:
                return m308(c, k);
            case 309:
                return m309(c, k);
            case 310:
                return m310(c, k);
            case 311:
                return m311(c, k);
            case 312:
                return m312(c, k);
            case 313:
                return m313(c, k);
            case 314:
                return m314(c, k);
            case 315:
                return m315(c, k);
            case 316:
                return m316(c, k);
            case 317:
                return m317(c, k);
            case 318:
                return m318(c, k);
            case 319:
                return m319(c, k);
            case 320:
                return m320(c, k);
            case 321:
                return m321(c, k);
            case 322:
                return m322(c, k);
            case 323:
                return m323(c, k);
            case 324:
                return m324(c, k);
            case 325:
                return m325(c, k);
            case 326:
                return m326(c, k);
            case 327:
                return m327(c, k);
            case 328:
                return m328(c, k);
            case 329:
                return m329(c, k);
            case 330:
                return m330(c, k);
            case 331:
                return m331(c, k);
            case 332:
                return m332(c, k);
            case 333:
                return m333(c, k);
            case 334:
                return m334(c, k);
            case 335:
                return m335(c, k);
            case 336:
                return m336(c, k);
            case 337:
                return m337(c, k);
            case 338:
                return m338(c, k);
            case 339:
                return m339(c, k);
            case 340:
                return m340(c, k);
            case 341:
                return m341(c, k);
            case 342:
                return m342(c, k);
            case 343:
                return m343(c, k);
            case 344:
                return m344(c, k);
            case 345:
                return m345(c, k);
            case 346:
                return m346(c, k);
            case 347:
                return m347(c, k);
            case 348:
                return m348(c, k);
            case 349:
                return m349(c, k);
            case 350:
                return m350(c, k);
            case 351:
                return m351(c, k);
            case 352:
                return m352(c, k);
            case 353:
                return m353(c, k);
            case 354:
                return m354(c, k);
            case 355:
                return m355(c, k);
            case 356:
                return m356(c, k);
            case 357:
                return m357(c, k);
            case 358:
                return m358(c, k);
            case 359:
                return m359(c, k);
            case 360:
                return m360(c, k);
            case 361:
                return m361(c, k);
            case 362:
                return m362(c, k);
            case 363:
                return m363(c, k);
            case 364:
                return m364(c, k);
            case 365:
                return m365(c, k);
            case 366:
                return m366(c, k);
            case 367:
                return m367(c, k);
            case 368:
                return m368(c, k);
            case 369:
                return m369(c, k);
            case 370:
                return m370(c, k);
            case 371:
                return m371(c, k);
            case 372:
                return m372(c, k);
            case 373:
                return m373(c, k);
            case 374:
                return m374(c, k);
            case 375:
                return m375(c, k);
            case 376:
                return m376(c, k);
            case 377:
                return m377(c, k);
            case 378:
                return m378(c, k);
            case 379:
                return m379(c, k);
            case 380:
                return m380(c, k);
            case 381:
                return m381(c, k);
            case 382:
                return m382(c, k);
            case 383:
                return m383(c, k);
            case 384:
                return m384(c, k);
            case 385:
                return m385(c, k);
            case 386:
                return m386(c, k);
            case 387:
                return m387(c, k);
            case 388:
                return m388(c, k);
            case 389:
                return m389(c, k);
            case 390:
                return m390(c, k);
            case 391:
                return m391(c, k);
            case 392:
                return m392(c, k);
            case 393:
                return m393(c, k);
            case 394:
                return m394(c, k);
            case 395:
                return m395(c, k);
            case 396:
                return m396(c, k);
            case 397:
                return m397(c, k);
            case 398:
                return m398(c, k);
            case 399:
                return m399(c, k);
            case 400:
                return m400(c, k);
            case 401:
                return m401(c, k);
            case 402:
                return m402(c, k);
            case 403:
                return m403(c, k);
            case 404:
                return m404(c, k);
            case 405:
                return m405(c, k);
            case 406:
                return m406(c, k);
            case 407:
                return m407(c, k);
            case 408:
                return m408(c, k);
            case 409:
                return m409(c, k);
            case 410:
                return m410(c, k);
            case 411:
                return m411(c, k);
            case 412:
                return m412(c, k);
            case 413:
                return m413(c, k);
            case 414:
                return m414(c, k);
            case 415:
                return m415(c, k);
            case 416:
                return m416(c, k);
            case 417:
                return m417(c, k);
            case 418:
                return m418(c, k);
            case 419:
                return m419(c, k);
            case 420:
                return m420(c, k);
            case 421:
                return m421(c, k);
            case 422:
                return m422(c, k);
            case 423:
                return m423(c, k);
            case 424:
                return m424(c, k);
            case 425:
                return m425(c, k);
            case 426:
                return m426(c, k);
            case 427:
                return m427(c, k);
            case 428:
                return m428(c, k);
            case 429:
                return m429(c, k);
            case 430:
                return m430(c, k);
            case 431:
                return m431(c, k);
            case 432:
                return m432(c, k);
            case 433:
                return m433(c, k);
            case 434:
                return m434(c, k);
            case 435:
                return m435(c, k);
            case 436:
                return m436(c, k);
            case 437:
                return m437(c, k);
            case 438:
                return m438(c, k);
            case 439:
                return m439(c, k);
            case 440:
                return m440(c, k);
            case 441:
                return m441(c, k);
            case 442:
                return m442(c, k);
            case 443:
                return m443(c, k);
            case 444:
                return m444(c, k);
            case 445:
                return m445(c, k);
            case 446:
                return m446(c, k);
            case 447:
                return m447(c, k);
            case 448:
                return m448(c, k);
            case 449:
                return m449(c, k);
            case 450:
                return m450(c, k);
            case 451:
                return m451(c, k);
            case 452:
                return m452(c, k);
            case 453:
                return m453(c, k);
            case 454:
                return m454(c, k);
            case 455:
                return m455(c, k);
            case 456:
                return m456(c, k);
            case 457:
                return m457(c, k);
            case 458:
                return m458(c, k);
            case 459:
                return m459(c, k);
            case 460:
                return m460(c, k);
            case 461:
                return m461(c, k);
            case 462:
                return m462(c, k);
            case 463:
                return m463(c, k);
            case 464:
                return m464(c, k);
            case 465:
                return m465(c, k);
            case 466:
                return m466(c, k);
            case 467:
                return m467(c, k);
            case 468:
                return m468(c, k);
            case 469:
                return m469(c, k);
            case 470:
                return m470(c, k);
            case 471:
                return m471(c, k);
            case 472:
                return m472(c, k);
            case 473:
                return m473(c, k);
            case 474:
                return m474(c, k);
            case 475:
                return m475(c, k);
            case 476:
                return m476(c, k);
            case 477:
                return m477(c, k);
            case 478:
                return m478(c, k);
            case 479:
                return m479(c, k);
            default:
                throw new IllegalStateException();
        }
    }

    public static void main(String[] args) throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong sum = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++)
        {
            final int stride = 2 * t + 1;
            ts[t] = new Thread(() ->
            {
                try
                {
                    go.await();
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }
                long s = 0;
                for (int j = 0; j < N; j++)
                {
                    int i = (j * stride + stride) % N;
                    s += call(i, calls, j);
                }
                sum.addAndGet(s);
            });
            ts[t].start();
        }
        go.countDown();
        for (Thread t : ts)
        {
            t.join();
        }
        System.out.println("calls = " + calls.get() + " (want " + (4 * N) + ")");
        System.out.println("sum = " + sum.get());
        System.out.println("FirstCallRaceStress done");
    }
}
