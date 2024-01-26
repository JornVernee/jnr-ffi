/*
 * Copyright (C) 2008-2010 Wayne Meissner
 *
 * This file is part of the JNR project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

import com.kenai.jffi.CallContext;
import com.kenai.jffi.Function;
import com.kenai.jffi.HeapInvocationBuffer;
import com.kenai.jffi.Internals;
import com.kenai.jffi.ObjectParameterType;
import jdk.jfr.MemoryAddress;
import jnr.ffi.Address;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.byref.ByReference;
import jnr.ffi.mapper.ToNativeContext;
import jnr.ffi.mapper.ToNativeConverter;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Utility methods that are used at runtime by generated code.
 */
public final class AsmRuntime {
    public static final com.kenai.jffi.MemoryIO IO = com.kenai.jffi.MemoryIO.getInstance();

    private AsmRuntime() {}

    public static UnsatisfiedLinkError newUnsatisifiedLinkError(String msg) {
        return new UnsatisfiedLinkError(msg);
    }

    public static HeapInvocationBuffer newHeapInvocationBuffer(Function function) {
        return new HeapInvocationBuffer(function);
    }

    public static HeapInvocationBuffer newHeapInvocationBuffer(CallContext callContext) {
        return new HeapInvocationBuffer(callContext);
    }

    public static HeapInvocationBuffer newHeapInvocationBuffer(CallContext callContext, int objCount) {
        return new HeapInvocationBuffer(callContext, objCount);
    }

    public static Pointer pointerValue(long ptr, jnr.ffi.Runtime runtime) {
        return ptr != 0 ? new DirectMemoryIO(runtime, ptr) : null;
    }

    public static Pointer pointerValue(int ptr, jnr.ffi.Runtime runtime) {
        return ptr != 0 ? new DirectMemoryIO(runtime, ptr) : null;
    }

    public static boolean isDirect(Pointer ptr) {
        return ptr == null || ptr.isDirect();
    }

    public static int intValue(Pointer ptr) {
        return ptr != null ? (int) ptr.address() : 0;
    }

    public static long longValue(Pointer ptr) {
        return ptr != null ? ptr.address() : 0L;
    }

    public static long longValue(Address ptr) {
        return ptr != null ? ptr.longValue() : 0L;
    }

    public static int intValue(Address ptr) {
        return ptr != null ? ptr.intValue() : 0;
    }

    public static ParameterStrategy nullParameterStrategy() {
        return NullObjectParameterStrategy.NULL;
    }

    public static PointerParameterStrategy directPointerParameterStrategy() {
        return PointerParameterStrategy.DIRECT;
    }

    public static PointerParameterStrategy pointerParameterStrategy(Pointer pointer) {
        if (pointer == null || pointer.isDirect()) {
            return PointerParameterStrategy.DIRECT;

        } else {
            return otherPointerParameterStrategy(pointer);
        }
    }

    private static PointerParameterStrategy otherPointerParameterStrategy(Pointer pointer) {
        if (pointer.hasArray()) {
            return PointerParameterStrategy.HEAP;

        } else {
            throw new RuntimeException("cannot convert " + pointer.getClass() + " to native");
        }
    }

    public static BufferParameterStrategy bufferParameterStrategy(Buffer buffer, ObjectParameterType.ComponentType componentType) {
        if (buffer == null || buffer.isDirect()) {
            return BufferParameterStrategy.direct(componentType);

        } else if (buffer.hasArray()) {
            return BufferParameterStrategy.heap(componentType);

        } else {
            throw new IllegalArgumentException("cannot marshal non-direct, non-array Buffer");
        }
    }

    public static BufferParameterStrategy pointerParameterStrategy(Buffer buffer) {
        if (buffer instanceof ByteBuffer) {
            return bufferParameterStrategy(buffer, ObjectParameterType.BYTE);

        } else if (buffer instanceof ShortBuffer) {
            return bufferParameterStrategy(buffer, ObjectParameterType.SHORT);

        } else if (buffer instanceof CharBuffer) {
            return bufferParameterStrategy(buffer, ObjectParameterType.CHAR);

        } else if (buffer instanceof IntBuffer) {
            return bufferParameterStrategy(buffer, ObjectParameterType.INT);

        } else if (buffer instanceof LongBuffer) {
            return bufferParameterStrategy(buffer, ObjectParameterType.LONG);

        } else if (buffer instanceof FloatBuffer) {
            return bufferParameterStrategy(buffer, ObjectParameterType.FLOAT);

        } else if (buffer instanceof DoubleBuffer) {
            return bufferParameterStrategy(buffer, ObjectParameterType.DOUBLE);

        } else if (buffer == null) {
                return BufferParameterStrategy.direct(ObjectParameterType.BYTE);

        } else {
            throw new IllegalArgumentException("unsupported java.nio.Buffer subclass: " + buffer.getClass());
        }
    }
    public static BufferParameterStrategy pointerParameterStrategy(ByteBuffer buffer) {
        return bufferParameterStrategy(buffer, ObjectParameterType.BYTE);
    }

    public static BufferParameterStrategy pointerParameterStrategy(ShortBuffer buffer) {
        return bufferParameterStrategy(buffer, ObjectParameterType.SHORT);
    }

    public static BufferParameterStrategy pointerParameterStrategy(CharBuffer buffer) {
        return bufferParameterStrategy(buffer, ObjectParameterType.CHAR);
    }

    public static BufferParameterStrategy pointerParameterStrategy(IntBuffer buffer) {
        return bufferParameterStrategy(buffer, ObjectParameterType.INT);
    }

    public static BufferParameterStrategy pointerParameterStrategy(LongBuffer buffer) {
        return bufferParameterStrategy(buffer, ObjectParameterType.LONG);
    }

    public static BufferParameterStrategy pointerParameterStrategy(FloatBuffer buffer) {
        return bufferParameterStrategy(buffer, ObjectParameterType.FLOAT);
    }

    public static BufferParameterStrategy pointerParameterStrategy(DoubleBuffer buffer) {
        return bufferParameterStrategy(buffer, ObjectParameterType.DOUBLE);
    }

    public static ParameterStrategy pointerParameterStrategy(byte[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.BYTE : NullObjectParameterStrategy.NULL;
    }

    public static ParameterStrategy pointerParameterStrategy(short[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.SHORT : NullObjectParameterStrategy.NULL;
    }

    public static ParameterStrategy pointerParameterStrategy(char[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.CHAR : NullObjectParameterStrategy.NULL;
    }

    public static ParameterStrategy pointerParameterStrategy(int[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.INT : NullObjectParameterStrategy.NULL;
    }

    public static ParameterStrategy pointerParameterStrategy(long[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.LONG : NullObjectParameterStrategy.NULL;
    }

    public static ParameterStrategy pointerParameterStrategy(float[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.FLOAT : NullObjectParameterStrategy.NULL;
    }

    public static ParameterStrategy pointerParameterStrategy(double[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.DOUBLE : NullObjectParameterStrategy.NULL;
    }

    public static ParameterStrategy pointerParameterStrategy(boolean[] array) {
        return array != null ? PrimitiveArrayParameterStrategy.BOOLEAN : NullObjectParameterStrategy.NULL;
    }

    public static void postInvoke(ToNativeConverter.PostInvocation postInvocation, Object j, Object n, ToNativeContext context) {
        try {
            postInvocation.postInvoke(j, n, context);
        } catch (Throwable t) {}
    }

    public static MethodHandle downcallHandle(MethodHandles.Lookup lookup, String name, Class<?> type, long addr, FunctionDescriptor descriptor) {
        assert type == MethodHandle.class;
        return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(addr), descriptor);
    }

    public static boolean intToBool(int i) {
        return i != 0;
    }

    public static MemorySegment ptrToAddr(SegmentAllocator allocator, Pointer ptr) {
        if (ptr == null) {
            return MemorySegment.NULL;
        }
        if (!ptr.isDirect()) {
            MemorySegment segment = allocator.allocate(ptr.size());
            for (long i = 0; i < ptr.size(); ++i) {
                segment.set(ValueLayout.JAVA_BYTE, i, ptr.getByte(i));
            }
            return segment;
        }
        return MemorySegment.ofAddress(ptr.address());
    }

    public static void copyBackToPtr(MemorySegment segment, Pointer to) {
        if (to == null || to.isDirect()) {
            return; // not needed
        }
        for (long i = 0; i < to.size(); ++i) {
            to.putByte(i, segment.get(ValueLayout.JAVA_BYTE, i));
        }
    }

    public static MemorySegment bufferOffHeap(SegmentAllocator allocator, MemorySegment segment) {
        if (!segment.isNative()) {
            MemorySegment offHeap = allocator.allocate(segment.byteSize());
            offHeap.copyFrom(segment);
            segment = offHeap;
        }
        return segment;
    }

    public static void copyFromArray(MemorySegment segment, byte[] array) {
        segment.copyFrom(MemorySegment.ofArray(array));
    }
    public static void copyFromArray(MemorySegment segment, short[] array) {
        segment.copyFrom(MemorySegment.ofArray(array));
    }
    public static void copyFromArray(MemorySegment segment, char[] array) {
        segment.copyFrom(MemorySegment.ofArray(array));
    }
    public static void copyFromArray(MemorySegment segment, int[] array) {
        segment.copyFrom(MemorySegment.ofArray(array));
    }
    public static void copyFromArray(MemorySegment segment, long[] array) {
        segment.copyFrom(MemorySegment.ofArray(array));
    }
    public static void copyFromArray(MemorySegment segment, float[] array) {
        segment.copyFrom(MemorySegment.ofArray(array));
    }
    public static void copyFromArray(MemorySegment segment, double[] array) {
        segment.copyFrom(MemorySegment.ofArray(array));
    }

    public static void copyToArray(MemorySegment segment, byte[] array) {
        MemorySegment.ofArray(array).copyFrom(segment);
    }
    public static void copyToArray(MemorySegment segment, short[] array) {
        MemorySegment.ofArray(array).copyFrom(segment);
    }
    public static void copyToArray(MemorySegment segment, char[] array) {
        MemorySegment.ofArray(array).copyFrom(segment);
    }
    public static void copyToArray(MemorySegment segment, int[] array) {
        MemorySegment.ofArray(array).copyFrom(segment);
    }
    public static void copyToArray(MemorySegment segment, long[] array) {
        MemorySegment.ofArray(array).copyFrom(segment);
    }
    public static void copyToArray(MemorySegment segment, float[] array) {
        MemorySegment.ofArray(array).copyFrom(segment);
    }
    public static void copyToArray(MemorySegment segment, double[] array) {
        MemorySegment.ofArray(array).copyFrom(segment);
    }

    public static MemorySegment refToAddr(Runtime runtime, SegmentAllocator allocator, ByReference<?> ref) {
        long size = ref.nativeSize(runtime);
        return allocator.allocate(size);
    }

    public static void copyFromRef(Runtime runtime, MemorySegment seg, ByReference<?> ref) {
        MemorySegmentPointer segPtr = new MemorySegmentPointer(runtime, seg);
        ref.toNative(runtime, segPtr, 0);
    }

    public static void copyToRef(Runtime runtime, MemorySegment seg, ByReference<?> ref) {
        MemorySegmentPointer segPtr = new MemorySegmentPointer(runtime, seg);
        ref.fromNative(runtime, segPtr, 0);
    }

    public static void copyToBuffer(MemorySegment seg, Buffer buff) {
        if (!buff.isDirect()) {
            MemorySegment.ofBuffer(buff).copyFrom(seg);
        }
    }

    static MethodHandle saveErrnoHandle() {
        class Holder {
            static final MethodHandle MH_SAVE_ERRNO;

            static {
                MemorySegment addr = MemorySegment.ofAddress(Internals.getErrnoSaveFunction());
                FunctionDescriptor func = FunctionDescriptor.ofVoid();
                MH_SAVE_ERRNO = Linker.nativeLinker().downcallHandle(addr, func);
            }
        }
        return Holder.MH_SAVE_ERRNO;
    }

    public static void saveErrno() throws Throwable {
        saveErrnoHandle().invokeExact();
    }

    // keep the same upcall stub per callback instance (required for JNR)
    private static final Map<Object, MemorySegment> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    public static MemorySegment bindCallback(Object callback, MethodHandle target, FunctionDescriptor descriptor) {
        if (callback == null) {
            return MemorySegment.NULL;
        }
        return CACHE.computeIfAbsent(callback, obj ->
            Linker.nativeLinker().upcallStub(target.bindTo(callback), descriptor, Arena.ofAuto()));
    }

}
