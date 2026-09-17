/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-11
 */
package java.lang;

/**
 * A JDK-free {@code java/lang/StackTraceElement}: one frame of a stack trace. The VM fills these natively
 * (see {@code Loader.frameToElement}) by writing the four fields directly at their slot offsets
 * (declaringClass@16, methodName@24, fileName@32, lineNumber@40 per ObjectModel), so the field DECLARATION
 * ORDER here is load-bearing -- do not reorder. No constructor runs on the metal path; the getters are what
 * a program (and this VM's tests) call. Compiled as a {@code java.base} patch.
 */
public final class StackTraceElement
{
    private String declaringClass;   // @16
    private String methodName;       // @24
    private String fileName;         // @32
    private int lineNumber;          // @40

    public StackTraceElement(String declaringClass, String methodName, String fileName, int lineNumber)
    {
        this.declaringClass = declaringClass;
        this.methodName = methodName;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    public String getClassName()
    {
        return declaringClass;
    }

    public String getMethodName()
    {
        return methodName;
    }

    public String getFileName()
    {
        return fileName;
    }

    public int getLineNumber()
    {
        return lineNumber;
    }

    public String toString()
    {
        return methodName;                 // simplified (no string-concat / invokedynamic); trace mode is off
    }
}
