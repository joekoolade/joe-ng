/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-20
 */

/**
 * Exception MESSAGES thrown by joe-ng's own engines ({@code crypto.Digest}, {@code crypto.Hmac},
 * {@code crypto.Pbkdf2}) when called directly. The writer bakes a string literal in non-{@code java/}/{@code jdk/}/
 * {@code sun/} code as a raw {@code byte[]}, so a message built there can come out wrong on metal while every
 * stock wrapper above it -- which validates first -- hides that. Each line prints the class and the message, and
 * the message's length so a byte[] posing as a String cannot pass by printing similar text.
 */
public class EngineMessageProbe
{
    interface Action
    {
        void run() throws Exception;
    }

    public static void main(String[] args)
    {
        System.out.println("Digest(99): " + attempt(() -> new crypto.Digest(99)));
        System.out.println("update(null): " + attempt(() -> new crypto.Digest(crypto.Digest.SHA256).update(null, 0, 0)));
        System.out.println("update range: " + attempt(() -> new crypto.Digest(crypto.Digest.SHA1).update(new byte[4], 3, 2)));
        System.out.println("digest(null): " + attempt(() -> new crypto.Digest(crypto.Digest.MD5).digest(null, 0)));
        System.out.println("digest range: " + attempt(() -> new crypto.Digest(crypto.Digest.MD5).digest(new byte[8], 0)));
        System.out.println("Hmac null key: " + attempt(() -> new crypto.Hmac(crypto.Digest.SHA256, null, 0)));
        System.out.println("Hmac key len: " + attempt(() -> new crypto.Hmac(crypto.Digest.SHA256, new byte[4], 9)));
        System.out.println("Hmac doFinal(null): " + attempt(() -> new crypto.Hmac(crypto.Digest.SHA1, new byte[4], 4).doFinal(null, 0)));
        System.out.println("Hmac doFinal range: " + attempt(() -> new crypto.Hmac(crypto.Digest.SHA1, new byte[4], 4).doFinal(new byte[30], 15)));
        System.out.println("Pbkdf2 null: " + attempt(() -> crypto.Pbkdf2.derive(crypto.Digest.SHA256, null, 0, new byte[1], 1, 1, new byte[8], 8)));
        System.out.println("Pbkdf2 salt len: " + attempt(() -> crypto.Pbkdf2.derive(crypto.Digest.SHA256, new byte[1], 1, new byte[1], 2, 1, new byte[8], 8)));
        System.out.println("Pbkdf2 dk len: " + attempt(() -> crypto.Pbkdf2.derive(crypto.Digest.SHA256, new byte[1], 1, new byte[1], 1, 1, new byte[8], 9)));
        System.out.println("Pbkdf2 iters: " + attempt(() -> crypto.Pbkdf2.derive(crypto.Digest.SHA256, new byte[1], 1, new byte[1], 1, 0, new byte[8], 8)));
        System.out.println("EngineMessageProbe done");
    }

    /** Runs {@code a} and names what it threw, its message, and the message's length; or "ok". */
    static String attempt(Action a)
    {
        try
        {
            a.run();
            return "ok";
        }
        catch (Exception e)
        {
            String m = e.getMessage();
            return e.getClass().getSimpleName() + " " + m + " len=" + (m == null ? -1 : m.length());
        }
    }
}
