/* Copyright (c) 2024, Oracle and/or its affiliates. */
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
        return segment.asSlice(offset, maxLength).getString(0, cs);
    }

    @Override
    public void putString(long offset, String string, int maxLength, Charset cs) {
        segment.asSlice(offset, maxLength).setString(0, string, cs);
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
