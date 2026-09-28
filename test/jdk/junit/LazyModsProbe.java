/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-28
 */
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Does a method's registry entry carry its real {@code access_flags} once its body has compiled LAZILY?
 *
 * <p>THE DEFECT. Since the stage-5 lazy-compile arc, {@code Loader.rememberLazyBody} registered a freshly
 * compiled body with a literal {@code 0} in the access word whenever the method had no registry entry yet --
 * which is every CELLED STATIC. A 0 there carries no {@code ACC_PUBLIC} bit, so
 * {@code Method.getModifiers()} answered 0 and {@code AccessibleObject.checkAccess} classified a plainly
 * public method as PACKAGE-PRIVATE, refusing a reflective call from any other package with
 * {@code IllegalAccessException}.
 *
 * <p>THE CONDITION IS "CALLED FIRST, REFLECTED SECOND", AND THAT IS WHY IT SURVIVED. A method already
 * registered by {@code registerAll} has the real flags, and {@code rememberLazyBody} only updates its
 * buffer -- so the bad path needs a method whose body compiles on FIRST CALL and which is then reflected
 * on. Reflect on it first and {@code methodResolve} takes the {@code compileMethodOnDemand} fork instead,
 * which records the flags correctly: the same method answers 0x9 or 0x0 depending on the ORDER, which is
 * exactly the shape that makes a defect look like a flake. Both orders are arms here.
 *
 * <p>THE PACKAGE SPLIT IS LOAD-BEARING. {@code checkAccess} returns early for {@code ACC_PUBLIC} and again
 * for a caller in the same package, so a target beside the probe would pass with the flags wrong. The target
 * is {@code demo.LazyModsTarget} and this probe is in the default package.
 *
 * <p>AND {@code pkg()} IS THE NEGATIVE. It is genuinely package-private, so the check must still REFUSE it
 * -- without that arm, "the flags are fixed" and "the check was disabled" look identical.
 *
 * <p>THE NEGATIVE CONTROL MEASURED THE DEFECT'S EXACT REACH, AND IT IS NARROWER THAN THE FIX SITE SUGGESTS:
 * reverting only the {@code accessInContext} call moves FOUR of these sixteen arms and they are all
 * {@code pub}'s. The other two targets are unaffected for reasons worth keeping, because both look like they
 * should move and do not:
 * <ul>
 *   <li>{@code inst} reads {@code 0x1} in BOTH states -- an instance method gets a DEFERRAL STUB, so it
 *       already has a registry entry from {@code registerAll} with the real flags, and
 *       {@code rememberLazyBody} only updates its buffer.</li>
 *   <li>{@code pkg} reads {@code 0x8} in BOTH -- it is never called directly, so {@code methodResolve} misses
 *       the registry and takes the {@code compileMethodOnDemand} fork, which records the flags correctly.</li>
 * </ul>
 * So the whole defect is one shape: a STATIC method, called directly first, reflected on second.
 */
public final class LazyModsProbe
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("lazy mods probe:");

        // THE CONDITION: call first, so the body compiles lazily and is registered by rememberLazyBody.
        System.out.println("  direct pub()               = " + demo.LazyModsTarget.pub());
        demo.LazyModsTarget t = new demo.LazyModsTarget();
        System.out.println("  direct inst()              = " + t.inst());

        Class<?> c = demo.LazyModsTarget.class;

        Method pub = c.getDeclaredMethod("pub");
        System.out.println("  pub  modifiers             = 0x" + Integer.toHexString(pub.getModifiers()));
        System.out.println("  pub  isPublic              = " + Modifier.isPublic(pub.getModifiers()));
        System.out.println("  pub  isStatic              = " + Modifier.isStatic(pub.getModifiers()));
        invoke("  pub  invoke (no setAccessible)", pub, null);

        Method inst = c.getDeclaredMethod("inst");
        System.out.println("  inst modifiers             = 0x" + Integer.toHexString(inst.getModifiers()));
        System.out.println("  inst isPublic              = " + Modifier.isPublic(inst.getModifiers()));
        System.out.println("  inst isStatic              = " + Modifier.isStatic(inst.getModifiers()));
        invoke("  inst invoke (no setAccessible)", inst, t);

        // THE NEGATIVE: genuinely package-private, and the check must still refuse it.
        Method pkg = c.getDeclaredMethod("pkg");
        System.out.println("  pkg  modifiers             = 0x" + Integer.toHexString(pkg.getModifiers()));
        System.out.println("  pkg  isPublic              = " + Modifier.isPublic(pkg.getModifiers()));
        invoke("  pkg  invoke (must be REFUSED)", pkg, null);
        pkg.setAccessible(true);
        invoke("  pkg  invoke after setAccessible", pkg, null);

        System.out.println("LazyModsProbe done");
    }

    private static void invoke(String what, Method m, Object recv)
    {
        try
        {
            System.out.println(what + " = " + m.invoke(recv));
        }
        catch (Throwable e)
        {
            System.out.println(what + " THREW " + e.getClass().getName());
        }
    }
}
