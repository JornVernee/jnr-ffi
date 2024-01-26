/*
 * Copyright (c) 2024  Oracle and/or its affiliates.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package jnr.ffi.provider.jffi;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jnr.ffi.Pointer;
import jnr.ffi.provider.AbstractMemoryIO;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class MemorySegmentPointer extends AbstractMemoryIO {

    private final MemorySegment segment;

    public MemorySegmentPointer(jnr.ffi.Runtime runtime, MemorySegment segment) {
        super(runtime, segment.address(), segment.isNative());
        this.segment = segment;
    }

    @Override
    public long size() {
        return segment.byteSize();
    }

    @Override
    public boolean hasArray() {
        return false;
    }

    @Override
    public Object array() {
        return null;
    }

    @Override
    public int arrayOffset() {
        return 0;
    }

    @Override
    public int arrayLength() {
        return 0;
    }

    public byte getByte(long offset) {
        return segment.get(ValueLayout.JAVA_BYTE, offset);
    }

    @Override
    public short getShort(long offset) {
        return segment.get(ValueLayout.JAVA_SHORT, offset);
    }

    @Override
    public int getInt(long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset);
    }

    @Override
    public long getLong(long offset) {
        return getLongLong(offset);
    }

    @Override
    public long getLongLong(long offset) {
        return segment.get(ValueLayout.JAVA_LONG, offset);
    }

    @Override
    public float getFloat(long offset) {
        return segment.get(ValueLayout.JAVA_FLOAT, offset);
    }

    @Override
    public double getDouble(long offset) {
        return segment.get(ValueLayout.JAVA_DOUBLE, offset);
    }

    @Override
    public void putByte(long offset, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, offset, value);
    }

    @Override
    public void putShort(long offset, short value) {
        segment.set(ValueLayout.JAVA_SHORT, offset, value);
    }

    @Override
    public void putInt(long offset, int value) {
        segment.set(ValueLayout.JAVA_INT, offset, value);
    }

    @Override
    public void putLongLong(long offset, long value) {
        segment.set(ValueLayout.JAVA_LONG, offset, value);
    }

    @Override
    public void putFloat(long offset, float value) {
        segment.set(ValueLayout.JAVA_FLOAT, offset, value);
    }

    @Override
    public void putDouble(long offset, double value) {
        segment.set(ValueLayout.JAVA_DOUBLE, offset, value);
    }

    @Override
    public void get(long offset, byte[] dst, int idx, int len) {
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, dst, idx, len);
    }

    @Override
    public void put(long offset, byte[] src, int idx, int len) {
        MemorySegment.copy(src, idx, segment, ValueLayout.JAVA_BYTE, offset, len);
    }

    @Override
    public void get(long offset, short[] dst, int idx, int len) {
        MemorySegment.copy(segment, ValueLayout.JAVA_SHORT, offset, dst, idx, len);
    }

    @Override
    public void put(long offset, short[] src, int idx, int len) {
        MemorySegment.copy(src, idx, segment, ValueLayout.JAVA_SHORT, offset, len);
    }

    @Override
    public void get(long offset, int[] dst, int idx, int len) {
        MemorySegment.copy(segment, ValueLayout.JAVA_INT, offset, dst, idx, len);
    }

    @Override
    public void put(long offset, int[] src, int idx, int len) {
        MemorySegment.copy(src, idx, segment, ValueLayout.JAVA_INT, offset, len);
    }

    @Override
    public void get(long offset, long[] dst, int idx, int len) {
        MemorySegment.copy(segment, ValueLayout.JAVA_LONG, offset, dst, idx, len);
    }

    @Override
    public void put(long offset, long[] src, int idx, int len) {
        MemorySegment.copy(src, idx, segment, ValueLayout.JAVA_LONG, offset, len);
    }

    @Override
    public void get(long offset, float[] dst, int idx, int len) {
        MemorySegment.copy(segment, ValueLayout.JAVA_FLOAT, offset, dst, idx, len);
    }

    @Override
    public void put(long offset, float[] src, int idx, int len) {
        MemorySegment.copy(src, idx, segment, ValueLayout.JAVA_FLOAT, offset, len);
    }

    @Override
    public void get(long offset, double[] dst, int idx, int len) {
        MemorySegment.copy(segment, ValueLayout.JAVA_DOUBLE, offset, dst, idx, len);
    }

    @Override
    public void put(long offset, double[] src, int idx, int len) {
        MemorySegment.copy(src, idx, segment, ValueLayout.JAVA_DOUBLE, offset, len);
    }

    @Override
    public Pointer getPointer(long offset) {
        return Pointer.wrap(getRuntime(), segment.get(ValueLayout.ADDRESS, offset).address());
    }

    @Override
    public Pointer getPointer(long offset, long size) {
        return Pointer.wrap(getRuntime(), segment.get(ValueLayout.ADDRESS, offset).address(), size);
    }

    @Override
    public void putPointer(long offset, Pointer value) {
        segment.set(ValueLayout.ADDRESS, offset, value != null
                ? MemorySegment.ofAddress(value.address())
                : MemorySegment.NULL);
    }

    @Override
    public String getString(long offset) {
        return segment.getString(offset);
    }

    @Override
    public String getString(long offset, int maxLength, Charset cs) {
        if (cs != StandardCharsets.UTF_8)
            throw new UnsupportedOperationException("No string length for arbitrary charset");
        return getString(offset);
    }

    @Override
    public void putString(long offset, String string, int maxLength, Charset cs) {
        if (cs != StandardCharsets.UTF_8)
            throw new UnsupportedOperationException("No string length for arbitrary charset");
        segment.setString(offset, string);
    }

    @Override
    public void setMemory(long offset, long size, byte value) {
        segment.asSlice(offset, size).fill(value);
    }

    @Override
    public int indexOf(long offset, byte value, int maxlen) {
        MemorySegment slice = segment.asSlice(offset, maxlen);
        for (int i = 0; i < slice.byteSize(); i++) {
            if (getByte(i) == value) {
                return i;
            }
        }
        return -1;
    }
}
