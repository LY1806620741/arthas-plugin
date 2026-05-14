package io.github.ly1806620741.arthas.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import com.alibaba.deps.org.objectweb.asm.ClassReader;
import com.alibaba.deps.org.objectweb.asm.ClassVisitor;
import com.alibaba.deps.org.objectweb.asm.ClassWriter;
import com.alibaba.deps.org.objectweb.asm.Label;
import com.alibaba.deps.org.objectweb.asm.MethodVisitor;
import com.alibaba.deps.org.objectweb.asm.Opcodes;
import com.alibaba.deps.org.objectweb.asm.Type;
import com.alibaba.deps.org.objectweb.asm.commons.AdviceAdapter;

import io.github.ly1806620741.arthas.OgnlContext;

final class AsmMockEnhancer {

    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type OBJECT_ARRAY_TYPE = Type.getType(Object[].class);
    private static final Type THROWABLE_TYPE = Type.getType(Throwable.class);
    private static final Type OGNL_CONTEXT_TYPE = Type.getType(OgnlContext.class);
    private static final Type COMPLETION_TYPE = Type.getType(MockCommand.OgnlMockAdvice.Completion.class);
    private static final Type CLASS_TYPE = Type.getType(Class.class);
    private static final Type STRING_TYPE = Type.getType(String.class);
    private static final Type ADVICE_TYPE = Type.getType(MockCommand.OgnlMockAdvice.class);

    private static final com.alibaba.deps.org.objectweb.asm.commons.Method ADVICE_INVOKE_METHOD =
            new com.alibaba.deps.org.objectweb.asm.commons.Method(
                    "invoke",
                    OGNL_CONTEXT_TYPE,
                    new Type[] { OBJECT_TYPE, CLASS_TYPE, STRING_TYPE, OBJECT_ARRAY_TYPE, Type.BOOLEAN_TYPE });
    private static final com.alibaba.deps.org.objectweb.asm.commons.Method ADVICE_IS_SKIPPED_METHOD =
            new com.alibaba.deps.org.objectweb.asm.commons.Method("isSkipped", Type.BOOLEAN_TYPE,
                    new Type[] { OGNL_CONTEXT_TYPE });
    private static final com.alibaba.deps.org.objectweb.asm.commons.Method ADVICE_COMPLETE_METHOD =
            new com.alibaba.deps.org.objectweb.asm.commons.Method("complete", COMPLETION_TYPE,
                    new Type[] { OGNL_CONTEXT_TYPE, CLASS_TYPE, OBJECT_TYPE, THROWABLE_TYPE });
    private static final com.alibaba.deps.org.objectweb.asm.commons.Method GET_RETURN_VALUE_METHOD =
            new com.alibaba.deps.org.objectweb.asm.commons.Method("getReturnValue", OBJECT_TYPE, new Type[0]);
    private static final com.alibaba.deps.org.objectweb.asm.commons.Method GET_THROWABLE_METHOD =
            new com.alibaba.deps.org.objectweb.asm.commons.Method("getThrowable", THROWABLE_TYPE, new Type[0]);

    private AsmMockEnhancer() {
    }

    static byte[] enhance(Class<?> clazz, Set<String> mockedMethodNames) throws IOException {
        if (mockedMethodNames.isEmpty()) {
            throw new IllegalArgumentException("No method matched for class: " + clazz.getName());
        }
        byte[] originalBytes = readClassBytes(clazz);
        ClassReader classReader = new ClassReader(originalBytes);
        ClassWriter classWriter = new LoaderAwareClassWriter(classReader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, clazz.getClassLoader());
        classReader.accept(new MockClassVisitor(classWriter, mockedMethodNames), ClassReader.EXPAND_FRAMES);
        return classWriter.toByteArray();
    }

    private static byte[] readClassBytes(Class<?> clazz) throws IOException {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        ClassLoader classLoader = clazz.getClassLoader();
        InputStream inputStream = classLoader != null
                ? classLoader.getResourceAsStream(resourceName)
                : ClassLoader.getSystemResourceAsStream(resourceName);
        if (inputStream == null) {
            throw new IOException("Unable to locate original class bytes for " + clazz.getName());
        }
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static final class MockClassVisitor extends ClassVisitor {

        private final Set<String> mockedMethodNames;
        private String classInternalName;

        private MockClassVisitor(ClassVisitor classVisitor, Set<String> mockedMethodNames) {
            super(Opcodes.ASM9, classVisitor);
            this.mockedMethodNames = mockedMethodNames;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.classInternalName = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (shouldEnhance(access, name)) {
                return new MockMethodVisitor(methodVisitor, access, name, descriptor,
                        Type.getObjectType(classInternalName));
            }
            return methodVisitor;
        }

        private boolean shouldEnhance(int access, String name) {
            return mockedMethodNames.contains(name)
                    && !"<init>".equals(name)
                    && !"<clinit>".equals(name)
                    && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
        }
    }

    private static final class MockMethodVisitor extends AdviceAdapter {

        private final Type ownerType;
        private final String methodName;
        private final Type returnType;
        private final Type[] argumentTypes;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchHandler = new Label();

        private int argsArrayLocal;
        private int contextLocal;
        private int completionLocal;
        private boolean tryBlockStarted;

        private MockMethodVisitor(MethodVisitor methodVisitor, int access, String name, String descriptor,
                Type ownerType) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.ownerType = ownerType;
            this.methodName = name;
            this.returnType = Type.getReturnType(descriptor);
            this.argumentTypes = Type.getArgumentTypes(descriptor);
        }

        @Override
        protected void onMethodEnter() {
            argsArrayLocal = newLocal(OBJECT_ARRAY_TYPE);
            contextLocal = newLocal(OGNL_CONTEXT_TYPE);
            completionLocal = newLocal(COMPLETION_TYPE);

            push(argumentTypes.length);
            newArray(OBJECT_TYPE);
            storeLocal(argsArrayLocal);

            for (int i = 0; i < argumentTypes.length; i++) {
                loadLocal(argsArrayLocal);
                push(i);
                loadArg(i);
                boxIfNeeded(argumentTypes[i]);
                arrayStore(OBJECT_TYPE);
            }

            if ((methodAccess & Opcodes.ACC_STATIC) != 0) {
                visitInsn(Opcodes.ACONST_NULL);
            } else {
                loadThis();
            }
            push(ownerType);
            push(methodName);
            loadLocal(argsArrayLocal);
            push(false);
            invokeStatic(ADVICE_TYPE, ADVICE_INVOKE_METHOD);
            storeLocal(contextLocal);

            Label continueLabel = newLabel();
            loadLocal(contextLocal);
            ifNull(continueLabel);

            for (int i = 0; i < argumentTypes.length; i++) {
                loadLocal(argsArrayLocal);
                push(i);
                arrayLoad(OBJECT_TYPE);
                castOrUnbox(argumentTypes[i]);
                storeArg(i);
            }

            loadLocal(contextLocal);
            invokeStatic(ADVICE_TYPE, ADVICE_IS_SKIPPED_METHOD);
            ifZCmp(EQ, continueLabel);

            invokeCompleteWithDefaultReturn();
            emitTerminalExit();

            mark(continueLabel);
            mark(tryStart);
            tryBlockStarted = true;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) {
                return;
            }

            int returnedObjectLocal = newLocal(OBJECT_TYPE);
            int thrownLocal = newLocal(THROWABLE_TYPE);
            if (Type.VOID_TYPE.equals(returnType)) {
                visitInsn(Opcodes.ACONST_NULL);
                storeLocal(returnedObjectLocal);
            } else {
                int originalReturnLocal = newLocal(returnType);
                storeLocal(originalReturnLocal);
                loadLocal(originalReturnLocal);
                boxIfNeeded(returnType);
                storeLocal(returnedObjectLocal);
            }

            visitInsn(Opcodes.ACONST_NULL);
            storeLocal(thrownLocal);
            invokeComplete(returnedObjectLocal, thrownLocal);
            emitCompletionThrowableCheck();

            if (!Type.VOID_TYPE.equals(returnType)) {
                loadLocal(completionLocal);
                invokeVirtual(COMPLETION_TYPE, GET_RETURN_VALUE_METHOD);
                castOrUnbox(returnType);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (tryBlockStarted) {
                mark(tryEnd);
                visitTryCatchBlock(tryStart, tryEnd, catchHandler, THROWABLE_TYPE.getInternalName());
                mark(catchHandler);
                int throwableLocal = newLocal(THROWABLE_TYPE);
                int returnedObjectLocal = newLocal(OBJECT_TYPE);
                storeLocal(throwableLocal);
                pushBoxedDefaultValue(returnType);
                storeLocal(returnedObjectLocal);
                invokeComplete(returnedObjectLocal, throwableLocal);
                emitTerminalExit();
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        private void invokeCompleteWithDefaultReturn() {
            int returnedObjectLocal = newLocal(OBJECT_TYPE);
            int thrownLocal = newLocal(THROWABLE_TYPE);
            pushBoxedDefaultValue(returnType);
            storeLocal(returnedObjectLocal);
            visitInsn(Opcodes.ACONST_NULL);
            storeLocal(thrownLocal);
            invokeComplete(returnedObjectLocal, thrownLocal);
        }

        private void invokeComplete(int returnedObjectLocal, int thrownLocal) {
            loadLocal(contextLocal);
            push(ownerType);
            loadLocal(returnedObjectLocal);
            loadLocal(thrownLocal);
            invokeStatic(ADVICE_TYPE, ADVICE_COMPLETE_METHOD);
            storeLocal(completionLocal);
        }

        private void emitCompletionThrowableCheck() {
            Label noThrowableLabel = newLabel();
            loadLocal(completionLocal);
            invokeVirtual(COMPLETION_TYPE, GET_THROWABLE_METHOD);
            ifNull(noThrowableLabel);
            loadLocal(completionLocal);
            invokeVirtual(COMPLETION_TYPE, GET_THROWABLE_METHOD);
            throwException();
            mark(noThrowableLabel);
        }

        private void emitTerminalExit() {
            emitCompletionThrowableCheck();
            if (Type.VOID_TYPE.equals(returnType)) {
                returnValue();
                return;
            }
            loadLocal(completionLocal);
            invokeVirtual(COMPLETION_TYPE, GET_RETURN_VALUE_METHOD);
            castOrUnbox(returnType);
            returnValue();
        }

        private void boxIfNeeded(Type type) {
            if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) {
                box(type);
            }
        }

        private void castOrUnbox(Type type) {
            if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                checkCast(type);
                return;
            }
            unbox(type);
        }

        private void pushBoxedDefaultValue(Type type) {
            switch (type.getSort()) {
            case Type.VOID:
            case Type.OBJECT:
            case Type.ARRAY:
                visitInsn(Opcodes.ACONST_NULL);
                return;
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                push(0);
                box(type);
                return;
            case Type.LONG:
                push(0L);
                box(type);
                return;
            case Type.FLOAT:
                push(0.0f);
                box(type);
                return;
            case Type.DOUBLE:
                push(0.0d);
                box(type);
                return;
            default:
                throw new IllegalStateException("Unsupported return type: " + type);
            }
        }
    }

    private static final class LoaderAwareClassWriter extends ClassWriter {

        private final ClassLoader classLoader;

        private LoaderAwareClassWriter(ClassReader classReader, int flags, ClassLoader classLoader) {
            super(classReader, flags);
            this.classLoader = classLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> class1 = loadClass(type1);
                Class<?> class2 = loadClass(type2);
                if (class1.isAssignableFrom(class2)) {
                    return type1;
                }
                if (class2.isAssignableFrom(class1)) {
                    return type2;
                }
                if (class1.isInterface() || class2.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    class1 = class1.getSuperclass();
                } while (class1 != null && !class1.isAssignableFrom(class2));
                return class1 == null ? "java/lang/Object" : Type.getInternalName(class1);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to resolve common super class for " + type1 + " and " + type2,
                        e);
            }
        }

        private Class<?> loadClass(String internalName) throws ClassNotFoundException {
            String className = internalName.replace('/', '.');
            if (classLoader != null) {
                return Class.forName(className, false, classLoader);
            }
            return Class.forName(className, false, ClassLoader.getSystemClassLoader());
        }
    }
}



