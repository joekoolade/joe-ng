/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-19
 */
package board.bcm2711;

import board.SdioHost;

/**
 * The Pi 4's SDIO host for its on-board WiFi chip: {@link Sdio}, the legacy Arasan EMMC block the CYW43455 is wired
 * to. {@code Bcm2711Platform.netUp} hands one to {@code board.cyw43.Cyw43} before bring-up.
 */
public final class Bcm2711SdioHost extends SdioHost
{
    @Override
    public int init()
    {
        return Sdio.init();
    }

    @Override
    public int control1()
    {
        return Sdio.control1();
    }

    @Override
    public void setClock(int hz)
    {
        Sdio.setClock(hz);
    }

    @Override
    public int cmd52Read(int func, int addr)
    {
        return Sdio.cmd52Read(func, addr);
    }

    @Override
    public boolean cmd52Write(int func, int addr, int data)
    {
        return Sdio.cmd52Write(func, addr, data);
    }

    @Override
    public boolean cmd53Read(int func, int addr, boolean incr, long dst, int blocks, int blkSize)
    {
        return Sdio.cmd53Read(func, addr, incr, dst, blocks, blkSize);
    }

    @Override
    public boolean cmd53Write(int func, int addr, boolean incr, long src, int blocks, int blkSize)
    {
        return Sdio.cmd53Write(func, addr, incr, src, blocks, blkSize);
    }

    @Override
    public void enableCardInt()
    {
        Sdio.enableCardInt();
    }

    @Override
    public void maskCardInt()
    {
        Sdio.maskCardInt();
    }

    @Override
    public void unmaskCardInt()
    {
        Sdio.unmaskCardInt();
    }

    @Override
    public void clearCardInt()
    {
        Sdio.clearCardInt();
    }
}
