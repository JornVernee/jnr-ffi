/* Copyright (c) 2024, Oracle and/or its affiliates. */
package jnr.ffi.provider.jffi;

import com.kenai.jffi.Function;
import jnr.ffi.Address;
import jnr.ffi.CallingConvention;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.byref.ByReference;
import jnr.ffi.provider.ParameterFlags;
import jnr.ffi.provider.ParameterType;
import jnr.ffi.provider.ResultType;
import jnr.ffi.provider.SigType;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

import java.lang.constant.ClassDesc;
import java.lang.constant.Constable;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.Buffer;

import static java.lang.invoke.MethodHandleInfo.REF_getField;
import static java.lang.invoke.MethodHandleInfo.REF_getStatic;
import static java.lang.invoke.MethodHandleInfo.REF_invokeInterface;
import static java.lang.invoke.MethodHandleInfo.REF_invokeSpecial;
import static java.lang.invoke.MethodHandleInfo.REF_invokeStatic;
import static java.lang.invoke.MethodHandleInfo.REF_invokeVirtual;
import static java.lang.invoke.MethodHandleInfo.REF_newInvokeSpecial;
import static java.lang.invoke.MethodHandleInfo.REF_putField;
import static java.lang.invoke.MethodHandleInfo.REF_putStatic;
import static jnr.ffi.provider.jffi.CodegenUtils.sig;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.H_GETFIELD;
import static org.objectweb.asm.Opcodes.H_GETSTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.H_INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.H_NEWINVOKESPECIAL;
import static org.objectweb.asm.Opcodes.H_PUTFIELD;
import static org.objectweb.asm.Opcodes.H_PUTSTATIC;

public class PanamaMethodGenerator implements MethodGenerator {

    private static final boolean IS_ENABLED = isEnabled();

    private static final Handle BSM_DOWNCALL_HANDLE = new Handle(
        H_INVOKESTATIC,
        Type.getInternalName(AsmRuntime.class),
        "downcallHandle",
        MethodType.methodType(MethodHandle.class, MethodHandles.Lookup.class, String.class, Class.class,
                long.class, FunctionDescriptor.class).descriptorString(),
        false
    );

    private static boolean isEnabled() {
        try {
            Linker.nativeLinker();
            return true;
        } catch (ExceptionInInitializerError e) {
            return false;
        }
    }

    private static boolean isSupportedType(SigType type, boolean isReturn) {
        Class<?> javaType = type.getDeclaredType();

        // TODO look at NativeLong
        boolean supportedType = (javaType.isPrimitive()
                || PanamaUtils.isWrapperType(javaType)
                || String.class == javaType
                || Pointer.class.isAssignableFrom(javaType)
                || (javaType.isArray() && javaType.getComponentType().isPrimitive())
                || Address.class == javaType
                || ByReference.class.isAssignableFrom(javaType)
                || (PanamaUtils.isCallback(javaType) && !isReturn)
                || Buffer.class.isAssignableFrom(javaType));

        if (!supportedType) {
            System.err.println("Unsupported type: " + javaType);
        }

        int flags = ParameterFlags.parse(type.annotations());
        boolean supportedFlags = !ParameterFlags.isPinned(flags);

        if (!supportedFlags) {
            System.err.println("Unsupported flags: " + flags);
        }

        // no funky types for now
        boolean supportedAnnotations = type.annotations().stream()
                .noneMatch(ann -> ann.annotationType().getPackageName().equals("jnr.ffi.types"));

        if (!supportedAnnotations) {
            System.err.println("Unsupported annotations: " + type.annotations());
        }

        return supportedType && supportedFlags && supportedAnnotations;
    }

    @Override
    public boolean isSupported(ResultType resultType, ParameterType[] parameterTypes, CallingConvention callingConvention) {
        if (!IS_ENABLED) {
            return false;
        }
        if (!isSupportedType(resultType, true)) {
            return false;
        }
        for (ParameterType pType : parameterTypes) {
            if (!isSupportedType(pType, false)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void generate(AsmBuilder builder, String functionName, Function function, ResultType resultType,
                         ParameterType[] parameterTypes, boolean ignoreError) {
        Class<?>[] javaParameterTypes = new Class[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            javaParameterTypes[i] = parameterTypes[i].getDeclaredType();
        }

        SkinnyMethodAdapter mv = new SkinnyMethodAdapter(builder.getClassVisitor(), ACC_PUBLIC | ACC_FINAL,
                functionName,
                sig(resultType.getDeclaredType(), javaParameterTypes), null, null);
        mv.start();

        LocalVariableAllocator localVariableAllocator = new LocalVariableAllocator(parameterTypes);

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchStart = new Label();
        mv.trycatch(tryStart, tryEnd, catchStart, Type.getInternalName(Throwable.class));

        LocalVariable scope = localVariableAllocator.allocate(Arena.class);
        mv.invokestatic(Arena.class, "ofConfined", Arena.class);
        mv.dup();
        mv.astore(scope);
        mv.visitLabel(tryStart);

        // convert args
        LocalVariable[] parameters = AsmUtil.getParameterVariables(parameterTypes);
        LocalVariable[] converted = new LocalVariable[parameterTypes.length];
        MemoryLayout[] paramLayouts = new MemoryLayout[parameterTypes.length];

        for (int i = 0; i < parameterTypes.length; i++) {
            ParameterType pType = parameterTypes[i];
            paramLayouts[i] = PanamaUtils.layoutFor(pType.getDeclaredType());
            converted[i] = emitConvertArg(builder, mv, pType, scope, parameters[i], localVariableAllocator);
        }

        FunctionDescriptor descriptor = resultType.effectiveJavaType() == void.class
                ? FunctionDescriptor.ofVoid(paramLayouts)
                : FunctionDescriptor.of(PanamaUtils.layoutFor(resultType.effectiveJavaType()), paramLayouts);

        MethodType type = descriptor.toMethodType();

        // call
        //   ldc handle
        mv.ldc(new ConstantDynamic(
            "getHandle",
            MethodHandle.class.descriptorString(),
            BSM_DOWNCALL_HANDLE,
            function.getFunctionAddress(),
            asAsmConstant(descriptor)
        ));
        //   push args
        for (LocalVariable arg : converted) {
            AsmUtil.load(mv, arg.type, arg);
        }
        //   invoke it
        mv.invokevirtual(MethodHandle.class, "invokeExact", type.returnType(), type.parameterArray());

        if (!ignoreError) { // FIXME use captureCallState
            AsmRuntime.saveErrnoHandle(); // trigger initialization now, since that will overwrite last error
            mv.invokestatic(AsmRuntime.class, "saveErrno", void.class);
        }

        // convert result
        emitConvertResult(builder, mv, resultType.getDeclaredType());

        // copy back native views to heap
        for (int i = 0; i < parameterTypes.length; i++) {
            ParameterType pType = parameterTypes[i];
            Class<?> declaredType = pType.getDeclaredType();
            int flags = ParameterFlags.parse(pType.annotations());
            if (Pointer.class.isAssignableFrom(declaredType)) {
                mv.aload(converted[i]);
                mv.aload(parameters[i]);
                mv.invokestatic(AsmRuntime.class, "copyBackToPtr", void.class, MemorySegment.class, Pointer.class);
            } else if (declaredType.isArray() && ParameterFlags.isOut(flags)) {
                mv.aload(converted[i]);
                mv.aload(parameters[i]);
                mv.invokestatic(AsmRuntime.class, "copyToArray", void.class, MemorySegment.class, declaredType);
            } else if (ByReference.class.isAssignableFrom(declaredType) && ParameterFlags.isOut(flags)) {
                AsmUtil.getfield(mv, builder, builder.getRuntimeField());
                mv.aload(converted[i]);
                mv.aload(parameters[i]);
                mv.invokestatic(AsmRuntime.class, "copyToRef", void.class, Runtime.class, MemorySegment.class, ByReference.class);
            } else if (Buffer.class.isAssignableFrom(declaredType) && ParameterFlags.isOut(flags)) {
                mv.aload(converted[i]);
                mv.aload(parameters[i]);
                mv.invokestatic(AsmRuntime.class, "copyToBuffer", void.class, MemorySegment.class, Buffer.class);
            }
        }

        mv.visitLabel(tryEnd);
        // finally
        emitCleanup(builder, mv, scope);
        emitReturn(mv, resultType.getDeclaredType());

        mv.visitLabel(catchStart);
        // finally
        emitCleanup(builder, mv, scope);
        mv.athrow();

        mv.visitMaxs(100, localVariableAllocator.getSpaceUsed());
        mv.visitEnd();
    }

    private static LocalVariable emitConvertArg(AsmBuilder builder, SkinnyMethodAdapter mv, ParameterType pType, LocalVariable allocator,
                                                LocalVariable parameter, LocalVariableAllocator localVariableAllocator) {
        LocalVariable result;
        Class<?> fromType = pType.getDeclaredType();
        int flags = ParameterFlags.parse(pType.annotations());
        if (fromType == String.class) {
            result = localVariableAllocator.allocate(MemorySegment.class);
            emitConvertFromString(mv, allocator, parameter, result);
        } else if (fromType.isPrimitive()) {
            result = parameter; // identity
        } else if (PanamaUtils.isWrapperType(fromType)) {
            Class<?> primType = AsmUtil.unboxedType(fromType);
            result = localVariableAllocator.allocate(primType);
            mv.aload(parameter);
            AsmUtil.unboxNumber(mv, fromType, primType);
            AsmUtil.store(mv, primType, result);
        } else if (fromType == Pointer.class) {
            result = localVariableAllocator.allocate(MemorySegment.class);
            mv.aload(allocator);
            mv.aload(parameter);
            mv.invokestatic(AsmRuntime.class, "ptrToAddr", MemorySegment.class, SegmentAllocator.class, Pointer.class);
            mv.astore(result);
        } else if (fromType == Address.class) {
            result = localVariableAllocator.allocate(MemorySegment.class);
            mv.aload(parameter);
            mv.invokevirtual(Address.class, "address", long.class);
            mv.invokestatic(MemorySegment.class, "ofAddress", MemorySegment.class, long.class);
            mv.astore(result);
        } else if(ByReference.class.isAssignableFrom(fromType)) {
            result = localVariableAllocator.allocate(MemorySegment.class);
            AsmUtil.getfield(mv, builder, builder.getRuntimeField());
            mv.aload(allocator);
            mv.aload(parameter);
            mv.invokestatic(AsmRuntime.class, "refToAddr", MemorySegment.class, Runtime.class, SegmentAllocator.class, ByReference.class);
            if (ParameterFlags.isIn(flags)) {
                mv.dup();
                AsmUtil.getfield(mv, builder, builder.getRuntimeField());
                mv.swap();
                mv.aload(parameter);
                mv.invokestatic(AsmRuntime.class, "copyFromRef", void.class, Runtime.class, MemorySegment.class, ByReference.class);
            }
            mv.astore(result);
        } else if (fromType.isArray()) {
            result = localVariableAllocator.allocate(MemorySegment.class);
            Class<?> componentType = fromType.getComponentType();
            long typeSize = sizeFor(componentType);
            mv.aload(allocator);
            mv.aload(parameter);
            mv.arraylength();
            mv.i2l();
            mv.ldc(typeSize);
            mv.lmul();
            mv.ldc(typeSize);
            mv.invokeinterface(SegmentAllocator.class, "allocate", MemorySegment.class, long.class, long.class);
            if (ParameterFlags.isIn(flags)) {
                mv.dup();
                mv.aload(parameter);
                mv.invokestatic(AsmRuntime.class, "copyFromArray", void.class, MemorySegment.class, fromType);
            }
            mv.astore(result);
        } else if (Buffer.class.isAssignableFrom(fromType)) {
            // deal with any type of buffer + heap buffers
            result = localVariableAllocator.allocate(MemorySegment.class);
            mv.aload(parameter);
            mv.invokestatic(MemorySegment.class, "ofBuffer", MemorySegment.class, Buffer.class);
            mv.astore(result);
            mv.aload(allocator);
            mv.aload(result);
            mv.invokestatic(AsmRuntime.class, "bufferOffHeap", MemorySegment.class, SegmentAllocator.class, MemorySegment.class);
            mv.astore(result);
        } else if (PanamaUtils.isCallback(fromType)) {
            result = localVariableAllocator.allocate(MemorySegment.class);
            mv.aload(parameter);
            Method delegate = PanamaUtils.getDelegateMethod(fromType);
            mv.ldc(asmHandle(delegate));
            mv.ldc(asAsmConstant(PanamaUtils.decriptorFor(delegate)));
            mv.invokestatic(AsmRuntime.class, "bindCallback", MemorySegment.class, Object.class, MethodHandle.class, FunctionDescriptor.class);
            mv.astore(result);
        } else {
            throw new UnsupportedOperationException("Can not convert from type: " + fromType);
        }
        return result;
    }

    private static Handle asmHandle(Method delegate) {
        Class<?> owner = delegate.getDeclaringClass();
        int ref = owner.isInterface() ? H_INVOKEINTERFACE : H_INVOKEVIRTUAL;
        return new Handle(
            ref,
            Type.getInternalName(owner),
            delegate.getName(),
            Type.getMethodDescriptor(delegate),
            owner.isInterface()
        );
    }

    // in bytes
    private static long sizeFor(Class<?> type) {
        if (type == boolean.class || type == byte.class) {
            return 1;
        } else if (type == short.class || type == char.class) {
            return 2;
        } else if (type == int.class || type == float.class) {
            return 4;
        } else if (type == long.class || type == double.class) {
            return 8;
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }

    private static void emitConvertResult(AsmBuilder builder, SkinnyMethodAdapter mv, Class<?> toType) {
        if (toType == String.class) {
            mv.lconst_0();
            mv.invokeinterface(MemorySegment.class, "getUtf8String", String.class, long.class);
        } else if (toType == Pointer.class) {
            mv.invokeinterface(MemorySegment.class, "address", long.class);
            AsmUtil.getfield(mv, builder, builder.getRuntimeField());
            mv.invokestatic(AsmRuntime.class, "pointerValue", Pointer.class, long.class, Runtime.class);
        } else if (toType.isPrimitive()) {
            // identity
            if (toType == boolean.class) {
                mv.invokestatic(AsmRuntime.class, "intToBool", boolean.class, int.class);
            }
        } else if (PanamaUtils.isWrapperType(toType)) {
            AsmUtil.boxValue(builder, mv, toType, AsmUtil.unboxedType(toType));
        }else if (toType == Address.class) {
            mv.invokeinterface(MemorySegment.class, "address", long.class);
            mv.invokestatic(Address.class, "valueOf", Address.class, long.class);
        } else {
            throw new UnsupportedOperationException("Can not convert to type: " + toType);
        }
    }

    private static void emitCleanup(AsmBuilder builder, SkinnyMethodAdapter mv, LocalVariable scope) {
        mv.aload(scope);
        mv.invokeinterface(Arena.class, "close", void.class);
    }

    private void emitReturn(SkinnyMethodAdapter mv, Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            mv.areturn();
        } else if (long.class == returnType) {
            mv.lreturn();
        } else if (float.class == returnType) {
            mv.freturn();
        } else if (double.class == returnType) {
            mv.dreturn();
        } else if (void.class == returnType) {
            mv.voidreturn();
        } else {
            mv.ireturn();
        }
    }

    private static Object asAsmConstant(Constable constant) {
        ConstantDesc desc = constant.describeConstable().orElseThrow();
        return asmConstant(desc);
    }

    private static Object asAsmConstant(FunctionDescriptor desc) {
        return asmConstant(PanamaUtils.describe(desc));
    }

    private static void emitConvertFromString(SkinnyMethodAdapter mv, LocalVariable allocator, LocalVariable parameter,
                                              LocalVariable converted) {
        mv.aload(allocator);
        mv.aload(parameter);
        mv.invokeinterface(SegmentAllocator.class, "allocateFrom", MemorySegment.class, String.class);
        mv.astore(converted);
    }

    private static String descriptorToInternalName(String s) {
        return s.substring(1, s.length() - 1);
    }

    private static Handle asmHandle(DirectMethodHandleDesc desc) {
        int tag = switch(desc.refKind()) {
            case REF_getField         -> H_GETFIELD;
            case REF_getStatic        -> H_GETSTATIC;
            case REF_putField         -> H_PUTFIELD;
            case REF_putStatic        -> H_PUTSTATIC;
            case REF_invokeVirtual    -> H_INVOKEVIRTUAL;
            case REF_invokeStatic     -> H_INVOKESTATIC;
            case REF_invokeSpecial    -> H_INVOKESPECIAL;
            case REF_newInvokeSpecial -> H_NEWINVOKESPECIAL;
            case REF_invokeInterface  -> H_INVOKEINTERFACE;
            default -> throw new InternalError("Should not reach here");
        };
        return new Handle(tag,
                descriptorToInternalName(desc.owner().descriptorString()),
                desc.methodName(),
                desc.lookupDescriptor(),
                desc.isOwnerInterface());
    }

    private static ConstantDynamic asmCondy(DynamicConstantDesc<?> condy) {
        return new ConstantDynamic(
                condy.constantName(),
                condy.constantType().descriptorString(),
                asmHandle(condy.bootstrapMethod()),
                asmConstantArgs(condy.bootstrapArgs()));
    }

    private static Object[] asmConstantArgs(ConstantDesc[] descs) {
        Object[] objects = new Object[descs.length];
        for (int i = 0; i < objects.length; i++) {
            objects[i] = asmConstant(descs[i]);
        }
        return objects;
    }

    private static Object asmConstant(ConstantDesc desc) {
        if (desc instanceof DynamicConstantDesc<?>) {
            return asmCondy((DynamicConstantDesc<?>) desc);
        } else if (desc instanceof Integer
            || desc instanceof Float
            || desc instanceof Long
            || desc instanceof Double
            || desc instanceof String) {
            return desc;
        } else if (desc instanceof ClassDesc) {
            // Primitives should be caught above
            return Type.getType(((ClassDesc) desc).descriptorString());
        } else if (desc instanceof MethodTypeDesc) {
            return Type.getMethodType(((MethodTypeDesc) desc).descriptorString());
        } else if (desc instanceof DirectMethodHandleDesc) {
            return asmHandle((DirectMethodHandleDesc) desc);
        }
        throw new IllegalArgumentException("ConstantDesc type not handled: " + desc);
    }
}
