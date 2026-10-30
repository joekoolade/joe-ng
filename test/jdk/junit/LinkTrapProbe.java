/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-29
 */

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;

/**
 * A call to a method that does not exist, in a class that is NOT denied, must be reported as a LINK TRAP -- not as a
 * DENYLIST TRAP, which sends the reader to a list that has nothing to do with it. javac cannot emit such a call, so
 * a class is built at run time whose {@code run()} does {@code invokestatic LinkTrapProbe.absent()V}. On the host
 * that throws {@code NoSuchMethodError}; on this VM the calling task halts on the trap, so the call runs in a daemon
 * thread and the main thread waits a bounded time and finishes. Metal-only by design: the gate is the trap header.
 */
public class LinkTrapProbe
{
    public static void main(String[] args) throws Exception
    {
        byte[] bytes = ClassFile.of().build(ClassDesc.of("LinkTrapProbe$Gen"), cb -> cb
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.invokestatic(ClassDesc.of("LinkTrapProbe"), "absent",
                                        MethodTypeDesc.of(ConstantDescs.CD_void))
                                .return_()));
        Class<?> c = MethodHandles.lookup().defineClass(bytes);
        Thread t = new Thread(() ->
        {
            try
            {
                c.getMethod("run").invoke(null);
                System.out.println("call returned");
            }
            catch (Throwable e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                System.out.println("call threw " + cause.getClass().getName());
            }
        });
        t.setDaemon(true);
        t.start();
        t.join(3000);
        System.out.println("LinkTrapProbe done");
    }
}
