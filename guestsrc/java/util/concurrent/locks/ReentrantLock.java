/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

/**
 * Name-winning {@code ReentrantLock} overlay for joe-ng. Stock {@code ReentrantLock} is backed by
 * {@code AbstractQueuedSynchronizer}, whose {@code <clinit>} builds {@code VarHandle}s via
 * {@code MethodHandles.lookup().findVarHandle(...)} -- the {@code java.lang.invoke} MethodHandle runtime,
 * which has no metal implementation (denied). That single {@code MethodHandles.lookup} call trapped on the
 * taken socket path (NioSocketImpl serializes read/write/connect through {@code readLock}/{@code writeLock}).
 *
 * <p>The socket path on metal is single-threaded (one blocking client, driven by one task), so mutual
 * exclusion is a no-op: {@code lock}/{@code unlock} do nothing, {@code tryLock} always succeeds, and
 * {@code isHeldByCurrentThread} is true (it only appears in {@code assert}s). This collapses the whole
 * AQS / MethodHandles / VarHandle / LockSupport / Node closure to nothing.
 */
public class ReentrantLock implements Lock, java.io.Serializable
{
    public ReentrantLock()
    {
    }

    public ReentrantLock(boolean fair)
    {
    }

    @Override
    public void lock()
    {
    }

    @Override
    public void lockInterruptibly() throws InterruptedException
    {
    }

    @Override
    public boolean tryLock()
    {
        return true;
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException
    {
        return true;
    }

    @Override
    public void unlock()
    {
    }

    @Override
    public Condition newCondition()
    {
        return null;   // never called on the socket path
    }

    public boolean isHeldByCurrentThread()
    {
        return true;   // single-threaded; only read in NioSocketImpl asserts
    }

    public boolean isLocked()
    {
        return false;
    }
}
