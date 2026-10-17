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
 * <p>The REGRESSION form of {@code FirstCallRaceStress}: 120 methods, small enough that no first-call compile holds
 * the loader lock long enough to trip its watchdog, so it prints only its answer and is diffed against the host. It
 * reproduced the SMP switch race fixed in {@code VMScheduler.markLeaving} only about once in 40 runs; the stress
 * form is the one that measures it. Generated; prints values only.
 */
public class FirstCallRaceProbe
{
    static final int N = 120;

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
        System.out.println("FirstCallRaceProbe done");
    }
}
