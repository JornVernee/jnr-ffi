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

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.SequenceLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import jnr.ffi.Address;
import jnr.ffi.Pointer;
import jnr.ffi.annotations.Delegate;
import jnr.ffi.byref.ByReference;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.lang.constant.ConstantDescs.BSM_GET_STATIC_FINAL;
import static java.lang.constant.ConstantDescs.BSM_INVOKE;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_long;

public class PanamaUtils {
    private static final Map<Class<?>, ValueLayout> PRIM_LAYOUTS = Map.of(
        // jnr treats boolean as C 'int' (32 bits), instead of the C 'bool' type (which is a single byte)
        // meaning that ints that have a 0 least significant byte
        // but have higher order bits set, are still interpreted as true
        // while panama treats boolean as C 'bool' and ignores anything outside the least significant byte.
        boolean.class, ValueLayout.JAVA_INT,
        byte.class, ValueLayout.JAVA_BYTE,
        short.class, ValueLayout.JAVA_SHORT,
        char.class, ValueLayout.JAVA_CHAR,
        int.class, ValueLayout.JAVA_INT,
        long.class, ValueLayout.JAVA_LONG,
        float.class, ValueLayout.JAVA_FLOAT,
        double.class, ValueLayout.JAVA_DOUBLE
    );

    public static MemoryLayout layoutFor(Class<?> javaType) {
        if (javaType.isPrimitive()) {
            return Objects.requireNonNull(PRIM_LAYOUTS.get(javaType));
        } else if (isWrapperType(javaType)) {
            return Objects.requireNonNull(PRIM_LAYOUTS.get(AsmUtil.unboxedType(javaType)));
        } else if (javaType.isArray()
                || Pointer.class.isAssignableFrom(javaType)
                || ByReference.class.isAssignableFrom(javaType)
                || javaType == Address.class
                || javaType == String.class
                || (isCallback(javaType))
                || Buffer.class.isAssignableFrom(javaType)) {
            return ValueLayout.ADDRESS;
        }
        throw new IllegalArgumentException("Unsupported type: " + javaType);
    }

    static boolean isWrapperType(Class<?> javaType) {
        return javaType == Boolean.class
                || javaType == Byte.class
                || javaType == Short.class
                || javaType == Character.class
                || javaType == Integer.class
                || javaType == Long.class
                || javaType == Float.class
                || javaType == Double.class;
    }

    public static boolean isCallback(Class<?> maybeCallbackClass) {
        return getDelegateMethod(maybeCallbackClass) != null;
    }

    static Method getDelegateMethod(Class<?> closureClass) {
        Method callMethod = null;
        for (Method m : closureClass.getMethods()) {
            if (m.isAnnotationPresent(Delegate.class) && Modifier.isPublic(m.getModifiers())
                    && !Modifier.isStatic(m.getModifiers())
                    && isPrimitveType(m)) {
                callMethod = m;
                break;
            }
        }
        return callMethod;
    }

    private static boolean isPrimitveType(Method m) {
        if (!m.getReturnType().isPrimitive()) {
            return false;
        }
        for (Class<?> type : m.getParameterTypes()) {
            if (!type.isPrimitive()) {
                return false;
            }
        }
        return true;
    }

    public static FunctionDescriptor decriptorFor(Method delegateMethod) {
        MemoryLayout[] paramLayouts = Arrays.stream(delegateMethod.getParameterTypes()).map(PanamaUtils::layoutFor).toArray(MemoryLayout[]::new);
        return delegateMethod.getReturnType() == void.class
                ? FunctionDescriptor.ofVoid(paramLayouts)
                : FunctionDescriptor.of(PanamaUtils.layoutFor(delegateMethod.getReturnType()), paramLayouts);
    }

    public static ConstantDesc describe(FunctionDescriptor desc) {
        List<ConstantDesc> constants = new ArrayList<>();
        constants.add(desc.returnLayout().isPresent() ? MH_FUNCTION : MH_VOID_FUNCTION);
        if (desc.returnLayout().isPresent()) {
            constants.add(describe(desc.returnLayout().get()));
        }
        for (MemoryLayout argLayout : desc.argumentLayouts()) {
            constants.add(describe(argLayout));
        }
        return DynamicConstantDesc.ofNamed(
                BSM_INVOKE, "function", CD_FUNCTION_DESC, constants.toArray(new ConstantDesc[0]));
    }

    public static ConstantDesc describe(MemoryLayout layout) {
        if (layout instanceof  ValueLayout vl) {
            ClassDesc constantType = valueLayoutTypeFor(vl);
            String constantName = valueLayoutConstantFor(vl);
            return decorateLayoutConstant(vl, DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, constantName,
                constantType, CD_VALUE_LAYOUT));
        } else if (layout instanceof GroupLayout gl) {
            ConstantDesc[] constants = new ConstantDesc[1 + gl.memberLayouts().size()];
            constants[0] = gl instanceof StructLayout ? MH_STRUCT : MH_UNION;
            for (int i = 0 ; i < gl.memberLayouts().size() ; i++) {
                constants[i + 1] = describe(gl.memberLayouts().get(i));
            }
            return decorateLayoutConstant(gl, DynamicConstantDesc.ofNamed(
                        ConstantDescs.BSM_INVOKE, gl instanceof StructLayout ? "structLayout" : "unionLayout",
                    CD_GROUP_LAYOUT, constants));
        }else if (layout instanceof SequenceLayout sl) {
            return decorateLayoutConstant(sl,
                        DynamicConstantDesc.ofNamed(ConstantDescs.BSM_INVOKE, "value",
                                CD_SEQUENCE_LAYOUT, MH_SIZED_SEQUENCE, sl.elementCount(), describe(sl.elementLayout())));
        }else if (layout instanceof PaddingLayout) {
            return decorateLayoutConstant(layout, DynamicConstantDesc.ofNamed(ConstantDescs.BSM_INVOKE, "padding",
                CD_MEMORY_LAYOUT, MH_PADDING, layout.byteSize()));
        } else {
            throw new IllegalArgumentException("Unknown layout type: " + layout);
        }
    }

    /*** Helper constants for implementing Layout::describeConstable ***/

    private static <T> DynamicConstantDesc<T> decorateLayoutConstant(MemoryLayout layout, DynamicConstantDesc<T> desc) {
        desc = DynamicConstantDesc.ofNamed(BSM_INVOKE, "withByteAlignment", desc.constantType(), MH_WITH_BYTE_ALIGNMENT,
                desc, layout.byteAlignment());
        if (layout.name().isPresent()) {
            desc = DynamicConstantDesc.ofNamed(BSM_INVOKE, "withName", desc.constantType(), MH_WITH_NAME,
                    desc, layout.name().get().describeConstable().orElseThrow());
        }

        return desc;
    }

    private static String valueLayoutConstantFor(ValueLayout vl) {
        if (vl instanceof ValueLayout.OfBoolean) {
            return "JAVA_BOOLEAN";
        } else if (vl instanceof ValueLayout.OfByte) {
            return "JAVA_BYTE";
        } else if (vl instanceof ValueLayout.OfShort) {
            return "JAVA_SHORT_UNALIGNED";
        } else if (vl instanceof ValueLayout.OfChar) {
            return "JAVA_CHAR_UNALIGNED";
        } else if (vl instanceof ValueLayout.OfInt) {
            return "JAVA_INT_UNALIGNED";
        } else if (vl instanceof ValueLayout.OfLong) {
            return "JAVA_LONG_UNALIGNED";
        } else if (vl instanceof ValueLayout.OfFloat) {
            return "JAVA_FLOAT_UNALIGNED";
        } else if (vl instanceof ValueLayout.OfDouble) {
            return "JAVA_DOUBLE_UNALIGNED";
        } else if (vl instanceof AddressLayout) {
            return "ADDRESS_UNALIGNED";
        } else {
            throw new IllegalStateException("Unknown type: " + vl);
        }
    }

    private static ClassDesc valueLayoutTypeFor(ValueLayout vl) {
        if (vl instanceof ValueLayout.OfBoolean) {
            return CD_ValueLayout_OfBoolean;
        } else if (vl instanceof ValueLayout.OfByte) {
            return CD_ValueLayout_OfByte;
        } else if (vl instanceof ValueLayout.OfShort) {
            return CD_ValueLayout_OfShort;
        } else if (vl instanceof ValueLayout.OfChar) {
            return CD_ValueLayout_OfChar;
        } else if (vl instanceof ValueLayout.OfInt) {
            return CD_ValueLayout_OfInt;
        } else if (vl instanceof ValueLayout.OfLong) {
            return CD_ValueLayout_OfLong;
        } else if (vl instanceof ValueLayout.OfFloat) {
            return CD_ValueLayout_OfFloat;
        } else if (vl instanceof ValueLayout.OfDouble) {
            return CD_ValueLayout_OfDouble;
        } else if (vl instanceof AddressLayout) {
            return CD_AddressLayout;
        } else {
            throw new IllegalStateException("Unknown type: " + vl);
        }
    }

    private static final ClassDesc CD_MEMORY_LAYOUT = MemoryLayout.class.describeConstable().get();
    private static final ClassDesc CD_VALUE_LAYOUT = ValueLayout.class.describeConstable().get();
    private static final ClassDesc CD_SEQUENCE_LAYOUT = SequenceLayout.class.describeConstable().get();
    private static final ClassDesc CD_GROUP_LAYOUT = GroupLayout.class.describeConstable().get();
    private static final ClassDesc CD_BYTEORDER = ByteOrder.class.describeConstable().get();
    private static final ClassDesc CD_FUNCTION_DESC = FunctionDescriptor.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfBoolean = ValueLayout.OfBoolean.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfByte = ValueLayout.OfByte.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfShort = ValueLayout.OfShort.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfChar = ValueLayout.OfChar.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfInt = ValueLayout.OfInt.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfLong = ValueLayout.OfLong.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfFloat = ValueLayout.OfFloat.class.describeConstable().get();
    private static final ClassDesc CD_ValueLayout_OfDouble = ValueLayout.OfDouble.class.describeConstable().get();
    private static final ClassDesc CD_AddressLayout = AddressLayout.class.describeConstable().get();

    private static final ConstantDesc BIG_ENDIAN = DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, "BIG_ENDIAN", CD_BYTEORDER, CD_BYTEORDER);

    private static final ConstantDesc LITTLE_ENDIAN = DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, "LITTLE_ENDIAN", CD_BYTEORDER, CD_BYTEORDER);

    private static final MethodHandleDesc MH_PADDING = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_STATIC, CD_MEMORY_LAYOUT, "paddingLayout",
                MethodTypeDesc.of(CD_MEMORY_LAYOUT, CD_long));

    private static final MethodHandleDesc MH_SIZED_SEQUENCE = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_STATIC, CD_MEMORY_LAYOUT, "sequenceLayout",
                MethodTypeDesc.of(CD_SEQUENCE_LAYOUT, CD_long, CD_MEMORY_LAYOUT));

    private static final MethodHandleDesc MH_STRUCT = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_STATIC, CD_MEMORY_LAYOUT, "structLayout",
                MethodTypeDesc.of(CD_GROUP_LAYOUT, CD_MEMORY_LAYOUT.arrayType()));

    private static final MethodHandleDesc MH_UNION = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_STATIC, CD_MEMORY_LAYOUT, "unionLayout",
                MethodTypeDesc.of(CD_GROUP_LAYOUT, CD_MEMORY_LAYOUT.arrayType()));

    private static final MethodHandleDesc MH_VOID_FUNCTION = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_STATIC, CD_FUNCTION_DESC, "ofVoid",
                MethodTypeDesc.of(CD_FUNCTION_DESC, CD_MEMORY_LAYOUT.arrayType()));

    private static final MethodHandleDesc MH_FUNCTION = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_STATIC, CD_FUNCTION_DESC, "of",
                MethodTypeDesc.of(CD_FUNCTION_DESC, CD_MEMORY_LAYOUT, CD_MEMORY_LAYOUT.arrayType()));

    private static final MethodHandleDesc MH_WITH_BYTE_ALIGNMENT = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL, CD_MEMORY_LAYOUT, "withByteAlignment",
                MethodTypeDesc.of(CD_MEMORY_LAYOUT, CD_long));

    private static final MethodHandleDesc MH_WITH_NAME = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL, CD_MEMORY_LAYOUT, "withName",
                MethodTypeDesc.of(CD_MEMORY_LAYOUT, CD_String));

}
