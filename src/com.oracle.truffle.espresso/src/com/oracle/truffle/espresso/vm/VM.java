/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.vm;

import static com.oracle.truffle.espresso.classfile.Constants.ACC_ABSTRACT;
import static com.oracle.truffle.espresso.classfile.Constants.ACC_CALLER_SENSITIVE;
import static com.oracle.truffle.espresso.classfile.Constants.ACC_FINAL;
import static com.oracle.truffle.espresso.classfile.Constants.ACC_LAMBDA_FORM_COMPILED;
import static com.oracle.truffle.espresso.classfile.Constants.ACC_PUBLIC;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_1;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_2;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_4;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_6;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_8;

import java.lang.reflect.Array;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.EspressoOptions;
import com.oracle.truffle.espresso.Utils;
import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.classfile.Constants;
import com.oracle.truffle.espresso.classfile.MethodParametersAttribute;
import com.oracle.truffle.espresso.classfile.RuntimeConstantPool;
import com.oracle.truffle.espresso.descriptors.ByteSequence;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.Symbol.Name;
import com.oracle.truffle.espresso.descriptors.Symbol.Signature;
import com.oracle.truffle.espresso.descriptors.Symbol.Type;
import com.oracle.truffle.espresso.descriptors.Types;
import com.oracle.truffle.espresso.descriptors.Validation;
import com.oracle.truffle.espresso.impl.ContextAccess;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.jni.Callback;
import com.oracle.truffle.espresso.jni.JNIHandles;
import com.oracle.truffle.espresso.jni.JniEnv;
import com.oracle.truffle.espresso.jni.JniImpl;
import com.oracle.truffle.espresso.jni.NativeEnv;
import com.oracle.truffle.espresso.jni.NativeLibrary;
import com.oracle.truffle.espresso.jni.Word;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.meta.MetaUtil;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.EspressoExitException;
import com.oracle.truffle.espresso.runtime.EspressoProperties;
import com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.substitutions.Host;
import com.oracle.truffle.espresso.substitutions.SuppressFBWarnings;
import com.oracle.truffle.espresso.substitutions.Target_java_lang_Class;
import com.oracle.truffle.espresso.substitutions.Target_java_lang_Object;
import com.oracle.truffle.espresso.substitutions.Target_java_lang_Thread;
import com.oracle.truffle.espresso.substitutions.Target_java_lang_Thread.State;

/**
 * Espresso implementation of the VM interface (libjvm).
 */
public final class VM extends NativeEnv implements ContextAccess {

    private static final TruffleLogger VMLogger = TruffleLogger.getLogger(EspressoLanguage.ID, VM.class);

    private static final InteropLibrary UNCACHED = InteropLibrary.getFactory().getUncached();

    private final TruffleObject initializeMokapotContext;
    private final TruffleObject disposeMokapotContext;

    private final TruffleObject initializeManagementContext;
    private final TruffleObject disposeManagementContext;

    private final TruffleObject getJavaVM;

    private final JniEnv jniEnv;

    private long managementPtr;

    public JNIHandles getHandles() {
        return jniEnv.getHandles();
    }

    private @Word long vmPtr;

    // mokapot.dll (Windows) or libmokapot.so (Unixes) is the Espresso implementation of the VM
    // interface (libjvm).
    // Espresso loads all shared libraries in a private namespace (e.g. using dlmopen on Linux).
    // libmokapot must be loaded strictly before any other library in the private namespace to
    // avoid linking with HotSpot libjvm, then libjava is loaded and further system libraries,
    // libzip, libnet, libnio ...
    private final TruffleObject mokapotLibrary;

    // libjava must be loaded after mokapot.
    private final TruffleObject javaLibrary;

    public TruffleObject getJavaLibrary() {
        return javaLibrary;
    }

    private VM(JniEnv jniEnv) {
        this.jniEnv = jniEnv;
        try {
            EspressoProperties props = getContext().getVmProperties();

            List<Path> libjavaSearchPaths = new ArrayList<>();
            libjavaSearchPaths.addAll(props.bootLibraryPath());
            libjavaSearchPaths.addAll(props.javaLibraryPath());

            mokapotLibrary = loadLibrary(props.espressoLibraryPath(), "mokapot");

            assert mokapotLibrary != null;
            javaLibrary = loadLibrary(libjavaSearchPaths, "java");

            initializeMokapotContext = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "initializeMokapotContext", "(env, sint64, (string): pointer): sint64");

            disposeMokapotContext = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "disposeMokapotContext",
                            "(env, sint64): void");

            initializeManagementContext = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "initializeManagementContext", "(env, (string): pointer): sint64");

            disposeManagementContext = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "disposeManagementContext",
                            "(env, sint64): void");

            getJavaVM = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "getJavaVM",
                            "(env): sint64");

            Callback lookupVmImplCallback = new Callback(LOOKUP_VM_IMPL_PARAMETER_COUNT, new Callback.Function() {
                @Override
                public Object call(Object... args) {
                    try {
                        return VM.this.lookupVmImpl((String) args[0]);
                    } catch (ClassCastException e) {
                        throw EspressoError.shouldNotReachHere(e);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Throwable e) {
                        throw EspressoError.shouldNotReachHere(e);
                    }
                }
            });
            this.vmPtr = (long) InteropLibrary.getFactory().getUncached().execute(initializeMokapotContext, jniEnv.getNativePointer(), lookupVmImplCallback);

            assert this.vmPtr != 0;

        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException | UnknownIdentifierException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @Override
    public EspressoContext getContext() {
        return jniEnv.getContext();
    }

    public @Word long getJavaVM() {
        try {
            return UNCACHED.asLong(UNCACHED.execute(getJavaVM));
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere("getJavaVM failed");
        }
    }

    private static Map<String, VMSubstitutor> buildVmMethods() {
        Map<String, VMSubstitutor> map = new HashMap<>();
        for (VMSubstitutor method : VMCollector.getInstance()) {
            assert !map.containsKey(method.methodName()) : "VmImpl for " + method + " already exists";
            map.put(method.methodName(), method);
        }
        return Collections.unmodifiableMap(map);
    }

    private static final Map<String, VMSubstitutor> vmMethods = buildVmMethods();

    public static VM create(JniEnv jniEnv) {
        return new VM(jniEnv);
    }

    private static final int JVM_CALLER_DEPTH = -1;

    public static final int LOOKUP_VM_IMPL_PARAMETER_COUNT = 1;

    public TruffleObject lookupVmImpl(String methodName) {
        VMSubstitutor m = vmMethods.get(methodName);
        try {
            // Dummy placeholder for unimplemented/unknown methods.
            if (m == null) {
                VMLogger.log(Level.FINER, "Fetching unknown/unimplemented VM method: {0}", methodName);
                return (TruffleObject) UNCACHED.execute(jniEnv.dupClosureRefAndCast("(pointer): void"),
                                new Callback(1, new Callback.Function() {
                                    @Override
                                    public Object call(Object... args) {
                                        CompilerDirectives.transferToInterpreter();
                                        VMLogger.log(Level.SEVERE, "Calling unimplemented VM method: {0}", methodName);
                                        throw EspressoError.unimplemented("VM method: " + methodName);
                                    }
                                }));
            }

            String signature = m.jniNativeSignature();
            Callback target = vmMethodWrapper(m);
            return (TruffleObject) InteropLibrary.getFactory().getUncached().execute(jniEnv.dupClosureRefAndCast(signature), target);

        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    // Checkstyle: stop method name check

    // region VM methods

    @VmImpl
    @JniImpl
    public static long JVM_CurrentTimeMillis(@SuppressWarnings("unused") StaticObject ignored) {
        return System.currentTimeMillis();
    }

    @VmImpl
    @JniImpl
    public static long JVM_NanoTime(@SuppressWarnings("unused") StaticObject ignored) {
        return System.nanoTime();
    }

    @VmImpl
    @JniImpl
    public static int JVM_IHashCode(@Host(Object.class) StaticObject object) {
        return System.identityHashCode(MetaUtil.maybeUnwrapNull(object));
    }

    @VmImpl
    @JniImpl
    public void JVM_ArrayCopy(@SuppressWarnings("unused") @Host(Class/* <System> */.class) StaticObject ignored,
                    @Host(Object.class) StaticObject src, int srcPos, @Host(Object.class) StaticObject dest, int destPos, int length) {
        try {
            if (src.isArray() && dest.isArray()) {
                System.arraycopy((src).unwrap(), srcPos, dest.unwrap(), destPos, length);
            } else {
                assert src.getClass().isArray();
                assert dest.getClass().isArray();
                System.arraycopy(src, srcPos, dest, destPos, length);
            }
        } catch (Exception e) {
            throw getMeta().throwExWithMessage(e.getClass(), e.getMessage());
        }
    }

    @VmImpl
    @JniImpl
    public static @Host(Object.class) StaticObject JVM_Clone(@Host(Object.class) StaticObject self) {
        assert StaticObject.notNull(self);
        if (self.isArray()) {
            // Arrays are always cloneable.
            return self.copy();
        }

        Meta meta = self.getKlass().getMeta();
        if (!meta.Cloneable.isAssignableFrom(self.getKlass())) {
            throw meta.throwEx(java.lang.CloneNotSupportedException.class);
        }

        if (InterpreterToVM.instanceOf(self, meta.Reference)) {
            // HotSpot 8202260: The semantics of cloning a Reference object is not clearly defined.
            // In addition, it is questionable whether it should be supported due to its tight
            // interaction with garbage collector.
            //
            // The reachability state of a Reference object may change during GC reference
            // processing. The referent may have been cleared when it reaches its reachability
            // state. On the other hand, it may be enqueued or pending for enqueuing. Cloning a
            // Reference object with a referent that is unreachable but not yet cleared might mean
            // to resurrect the referent. A cloned enqueued Reference object will never be enqueued.
            //
            // A Reference object cannot be meaningfully cloned.

            // Non-strong references are not cloneable.
            if (InterpreterToVM.instanceOf(self, meta.WeakReference) //
                            || InterpreterToVM.instanceOf(self, meta.SoftReference) //
                            || InterpreterToVM.instanceOf(self, meta.FinalReference) //
                            || InterpreterToVM.instanceOf(self, meta.PhantomReference)) {

                throw meta.throwExWithMessage(java.lang.CloneNotSupportedException.class, self.getKlass().getName().toString());
            }
        }

        final StaticObject clone = self.copy();

        // If the original object is finalizable, so is the copy.
        assert self.getKlass() instanceof ObjectKlass;
        if (((ObjectKlass) self.getKlass()).hasFinalizer()) {
            Target_java_lang_Object.registerFinalizer(clone);
        }

        return clone;
    }

    public Callback vmMethodWrapper(VMSubstitutor m) {
        int extraArg = (m.isJni()) ? 1 : 0;

        return new Callback(m.parameterCount() + extraArg, new Callback.Function() {
            @Override
            @CompilerDirectives.TruffleBoundary
            public Object call(Object... args) {
                boolean isJni = m.isJni();
                try {
                    return m.invoke(VM.this, args);
                } catch (EspressoException e) {
                    if (isJni) {
                        jniEnv.getThreadLocalPendingException().set(e.getExceptionObject());
                        return defaultValue(m.returnType());
                    }
                    throw EspressoError.shouldNotReachHere(e);
                } catch (StackOverflowError | OutOfMemoryError e) {
                    if (isJni) {
                        // This will most likely SOE again. Nothing we can do about that
                        // unfortunately.
                        jniEnv.getThreadLocalPendingException().set(getMeta().initEx(e.getClass()));
                        return defaultValue(m.returnType());
                    }
                    throw e;
                } catch (RuntimeException | VirtualMachineError e) {
                    throw e;
                } catch (ThreadDeath e) {
                    throw getMeta().throwEx(ThreadDeath.class);
                } catch (Throwable e) {
                    throw EspressoError.shouldNotReachHere(e);
                }
            }
        });
    }

    @VmImpl
    @JniImpl
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .notifyAll is just forwarded from the guest.")
    public void JVM_MonitorNotifyAll(@Host(Object.class) StaticObject self) {
        try {
            self.getLock().signalAll();
        } catch (IllegalMonitorStateException e) {
            throw getMeta().throwExWithMessage(e.getClass(), e.getMessage());
        }
    }

    @VmImpl
    @JniImpl
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .notify is just forwarded from the guest.")
    public void JVM_MonitorNotify(@Host(Object.class) StaticObject self) {
        try {
            self.getLock().signal();
        } catch (IllegalMonitorStateException e) {
            throw getMeta().throwExWithMessage(e.getClass(), e.getMessage());
        }
    }

    @VmImpl
    @JniImpl
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .wait is just forwarded from the guest.")
    public void JVM_MonitorWait(@Host(Object.class) StaticObject self, long timeout) {
        StaticObject currentThread = getMeta().getContext().getCurrentThread();
        try {
            Target_java_lang_Thread.fromRunnable(currentThread, getMeta(), (timeout > 0 ? State.TIMED_WAITING : State.WAITING));
            self.getLock().await(timeout);
        } catch (InterruptedException e) {
            Target_java_lang_Thread.setInterrupt(currentThread, false);
            throw getMeta().throwExWithMessage(e.getClass(), e.getMessage());
        } catch (IllegalMonitorStateException | IllegalArgumentException e) {
            throw getMeta().throwExWithMessage(e.getClass(), e.getMessage());
        } finally {
            Target_java_lang_Thread.toRunnable(currentThread, getMeta(), State.RUNNABLE);
        }
    }

    @VmImpl
    public static boolean JVM_IsNaN(double d) {
        return Double.isNaN(d);
    }

    @VmImpl
    public static boolean JVM_SupportsCX8() {
        try {
            Class<?> klass = Class.forName("java.util.concurrent.atomic.AtomicLong");
            java.lang.reflect.Field field = klass.getDeclaredField("VM_SUPPORTS_LONG_CAS");
            field.setAccessible(true);
            return field.getBoolean(null);
        } catch (IllegalAccessException | NoSuchFieldException | ClassNotFoundException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @VmImpl
    @JniImpl
    // TODO(peterssen): @Type annotaion only for readability purposes.
    public @Host(String.class) StaticObject JVM_InternString(@Host(String.class) StaticObject self) {
        return getInterpreterToVM().intern(self);
    }

    // endregion VM methods

    // region JNI Invocation Interface
    @VmImpl
    public static int DestroyJavaVM() {
        return JniEnv.JNI_OK;
    }

    @SuppressWarnings("unused")
    @VmImpl
    public static int AttachCurrentThread(@Word long penvPtr, @Word long argsPtr) {
        VMLogger.warning("Calling AttachCurrentThread! " + penvPtr + " " + Thread.currentThread());
        EspressoLanguage.getCurrentContext().createThread(Thread.currentThread());
        return JniEnv.JNI_OK;
    }

    @VmImpl
    public static int DetachCurrentThread() {
        VMLogger.warning("DetachCurrentThread!!!" + Thread.currentThread());
        EspressoLanguage.getCurrentContext().disposeThread(Thread.currentThread());
        return JniEnv.JNI_OK;
    }

    /**
     * <h3>jint GetEnv(JavaVM *vm, void **env, jint version);</h3>
     *
     * @param vmPtr_ The virtual machine instance from which the interface will be retrieved.
     * @param envPtr pointer to the location where the JNI interface pointer for the current thread
     *            will be placed.
     * @param version The requested JNI version.
     *
     * @returns If the current thread is not attached to the VM, sets *env to NULL, and returns
     *          JNI_EDETACHED. If the specified version is not supported, sets *env to NULL, and
     *          returns JNI_EVERSION. Otherwise, sets *env to the appropriate interface, and returns
     *          JNI_OK.
     */
    @SuppressWarnings("unused")
    @VmImpl
    public int GetEnv(@Word long vmPtr_, @Word long envPtr, int version) {
        // TODO(peterssen): Check the thread is attached, and that the VM pointer matches.
        LongBuffer buf = directByteBuffer(envPtr, 1, JavaKind.Long).asLongBuffer();
        buf.put(jniEnv.getNativePointer());
        return JniEnv.JNI_OK;
    }

    @SuppressWarnings("unused")
    @VmImpl
    public static int AttachCurrentThreadAsDaemon(@Word long penvPtr, @Word long argsPtr) {
        return JniEnv.JNI_OK;
    }

    // endregion JNI Invocation Interface

    public static class StackElement {
        private final Method m;
        private final int bci;

        public StackElement(Method m, int bci) {
            this.m = m;
            this.bci = bci;
        }

        public Method getMethod() {
            return m;
        }

        public int getBCI() {
            return bci;
        }
    }

    public static class StackTrace {
        public StackElement[] trace;
        public int size;
        public int capacity;

        public StackTrace() {
            this.trace = new StackElement[EspressoContext.DEFAULT_STACK_SIZE];
            this.capacity = EspressoContext.DEFAULT_STACK_SIZE;
            this.size = 0;
        }

        public void add(StackElement e) {
            if (size < capacity) {
                trace[size++] = e;
            } else {
                trace = Arrays.copyOf(trace, capacity <<= 1);
                trace[size++] = e;
            }
        }
    }

    @VmImpl
    @JniImpl
    public @Host(Throwable.class) StaticObject JVM_FillInStackTrace(@Host(Throwable.class) StaticObject self, @SuppressWarnings("unused") int dummy) {
        InterpreterToVM.fillInStackTrace(self, false, getMeta());
        return self;
    }

    @VmImpl
    @JniImpl
    public int JVM_GetStackTraceDepth(@Host(Throwable.class) StaticObject self) {
        Meta meta = getMeta();
        StackTrace frames = EspressoException.getFrames(self, meta);
        if (frames == null) {
            return 0;
        }
        return frames.size;
    }

    @VmImpl
    @JniImpl
    public @Host(StackTraceElement.class) StaticObject JVM_GetStackTraceElement(@Host(Throwable.class) StaticObject self, int index) {
        Meta meta = getMeta();
        if (index < 0) {
            throw meta.throwEx(IndexOutOfBoundsException.class);
        }
        StaticObject ste = meta.StackTraceElement.allocateInstance();
        StackTrace frames = EspressoException.getFrames(self, meta);
        if (frames == null || index >= frames.size) {
            throw meta.throwEx(IndexOutOfBoundsException.class);
        }
        StackElement stackElement = frames.trace[index];
        Method method = stackElement.getMethod();
        if (method == null) {
            return StaticObject.NULL;
        }
        int bci = stackElement.getBCI();

        meta.StackTraceElement_init.invokeDirect(
                        /* this */ ste,
                        /* declaringClass */ meta.toGuestString(MetaUtil.internalNameToJava(method.getDeclaringKlass().getType().toString(), true, true)),
                        /* methodName */ meta.toGuestString(method.getName()),
                        /* fileName */ meta.toGuestString(method.getSourceFile()),
                        /* lineNumber */ method.bciToLineNumber(bci));

        return ste;
    }

    private static void checkTag(ConstantPool pool, int index, ConstantPool.Tag expected) {
        ConstantPool.Tag target = pool.tagAt(index);
        if (target != expected) {
            throw EspressoLanguage.getCurrentContext().getMeta().throwExWithMessage(IllegalArgumentException.class, "Wrong type at constant pool index");
        }
    }

    // region ConstantPool

    @VmImpl
    @JniImpl
    public static int JVM_ConstantPoolGetSize(@SuppressWarnings("unused") StaticObject unused, StaticObject jcpool) {
        return jcpool.getMirrorKlass().getConstantPool().length();
    }

    @VmImpl
    @JniImpl
    public static @Host(Class.class) StaticObject JVM_ConstantPoolGetClassAt(@SuppressWarnings("unused") StaticObject unused, @Host(Object.class) StaticObject jcpool, int index) {
        checkTag(jcpool.getMirrorKlass().getConstantPool(), index, ConstantPool.Tag.CLASS);
        return ((RuntimeConstantPool) jcpool.getMirrorKlass().getConstantPool()).resolvedKlassAt(null, index).mirror();
    }

    @VmImpl
    @JniImpl
    public static double JVM_ConstantPoolGetDoubleAt(@SuppressWarnings("unused") StaticObject unused, @Host(Object.class) StaticObject jcpool, int index) {
        checkTag(jcpool.getMirrorKlass().getConstantPool(), index, ConstantPool.Tag.DOUBLE);
        return jcpool.getMirrorKlass().getConstantPool().doubleAt(index);
    }

    @VmImpl
    @JniImpl
    public static float JVM_ConstantPoolGetFloatAt(@SuppressWarnings("unused") StaticObject unused, @Host(Object.class) StaticObject jcpool, int index) {
        checkTag(jcpool.getMirrorKlass().getConstantPool(), index, ConstantPool.Tag.FLOAT);
        return jcpool.getMirrorKlass().getConstantPool().floatAt(index);
    }

    @VmImpl
    @JniImpl
    public static @Host(String.class) StaticObject JVM_ConstantPoolGetStringAt(@SuppressWarnings("unused") StaticObject unused, @Host(Object.class) StaticObject jcpool, int index) {
        checkTag(jcpool.getMirrorKlass().getConstantPool(), index, ConstantPool.Tag.STRING);
        return ((RuntimeConstantPool) jcpool.getMirrorKlass().getConstantPool()).resolvedStringAt(index);
    }

    @VmImpl
    @JniImpl
    public @Host(String.class) StaticObject JVM_ConstantPoolGetUTF8At(@SuppressWarnings("unused") StaticObject unused, StaticObject jcpool, int index) {
        checkTag(jcpool.getMirrorKlass().getConstantPool(), index, ConstantPool.Tag.UTF8);
        return getMeta().toGuestString(jcpool.getMirrorKlass().getConstantPool().symbolAt(index).toString());
    }

    @VmImpl
    @JniImpl
    public static int JVM_ConstantPoolGetIntAt(@SuppressWarnings("unused") StaticObject unused, StaticObject jcpool, int index) {
        checkTag(jcpool.getMirrorKlass().getConstantPool(), index, ConstantPool.Tag.INTEGER);
        return jcpool.getMirrorKlass().getConstantPool().intAt(index);
    }

    @VmImpl
    @JniImpl
    public static long JVM_ConstantPoolGetLongAt(@SuppressWarnings("unused") StaticObject unused, StaticObject jcpool, int index) {
        checkTag(jcpool.getMirrorKlass().getConstantPool(), index, ConstantPool.Tag.LONG);
        return jcpool.getMirrorKlass().getConstantPool().longAt(index);
    }

    // endregion ConstantPool

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_DefineClass(String name, @Host(ClassLoader.class) StaticObject loader, @Word long bufPtr, int len,
                    @Host(ProtectionDomain.class) StaticObject pd) {
        ByteBuffer buf = JniEnv.directByteBuffer(bufPtr, len, JavaKind.Byte);
        final byte[] bytes = new byte[len];
        buf.get(bytes);

        Symbol<Type> type = null;
        if (name != null) {
            String internalName = name;
            if (!name.startsWith("[")) {
                // Force 'L' type.
                internalName = "L" + name + ";";
            }
            if (!Validation.validTypeDescriptor(ByteSequence.create(internalName), false)) {
                throw getMeta().throwExWithMessage(NoClassDefFoundError.class, name);
            }
            type = getTypes().fromClassGetName(internalName);
        }

        StaticObject clazz = getContext().getRegistries().defineKlass(type, bytes, loader).mirror();
        assert clazz != null;
        assert pd != null;
        clazz.setHiddenField(getMeta().HIDDEN_PROTECTION_DOMAIN, pd);
        return clazz;
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_DefineClassWithSource(String name, @Host(ClassLoader.class) StaticObject loader, @Word long bufPtr, int len,
                    @Host(ProtectionDomain.class) StaticObject pd, @SuppressWarnings("unused") String source) {
        // FIXME(peterssen): Source is ignored.
        return JVM_DefineClass(name, loader, bufPtr, len, pd);
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_FindLoadedClass(@Host(ClassLoader.class) StaticObject loader, @Host(String.class) StaticObject name) {
        Symbol<Type> type = getTypes().fromClassGetName(Meta.toHostString(name));
        Klass klass = getRegistries().findLoadedClass(type, loader);
        if (klass == null) {
            return StaticObject.NULL;
        }
        return klass.mirror();
    }

    private final ConcurrentHashMap<Long, TruffleObject> handle2Lib = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, TruffleObject> handle2Sym = new ConcurrentHashMap<>();

    // region Library support
    @VmImpl
    public @Word long JVM_LoadLibrary(String name) {
        try {
            TruffleObject lib = NativeLibrary.loadLibrary(Paths.get(name));
            java.lang.reflect.Field f = lib.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            long handle = (long) f.get(lib);
            handle2Lib.put(handle, lib);
            return handle;
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @VmImpl
    public static void JVM_UnloadLibrary(@SuppressWarnings("unused") @Word long handle) {
        // TODO(peterssen): Do unload the library.
        VMLogger.severe("JVM_UnloadLibrary called but library was not unloaded!");
    }

    @VmImpl
    public @Word long JVM_FindLibraryEntry(@Word long libHandle, String name) {
        if (libHandle == 0) {
            VMLogger.warning("JVM_FindLibraryEntry from default/global namespace (0): " + name);
            return 0L;
        }
        // TODO(peterssen): Workaround for MacOS flags: RTLD_DEFAULT...
        if (-6 < libHandle && libHandle < 0) {
            VMLogger.warning("JVM_FindLibraryEntry with unsupported flag/handle/namespace (" + libHandle + "): " + name);
            return 0L;
        }
        try {
            TruffleObject function = NativeLibrary.lookup(handle2Lib.get(libHandle), name);
            long handle = InteropLibrary.getFactory().getUncached().asPointer(function);
            handle2Sym.put(handle, function);
            return handle;
        } catch (UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        } catch (UnknownIdentifierException e) {
            return 0; // not found
        }
    }

    // endregion Library support
    @VmImpl
    public static boolean JVM_IsSupportedJNIVersion(int version) {
        return version == JNI_VERSION_1_1 ||
                        version == JNI_VERSION_1_2 ||
                        version == JNI_VERSION_1_4 ||
                        version == JNI_VERSION_1_6 ||
                        version == JNI_VERSION_1_8;
    }

    @VmImpl
    public static int JVM_GetInterfaceVersion() {
        return JniEnv.JVM_INTERFACE_VERSION;
    }

    public void dispose() {
        assert vmPtr != 0L : "Mokapot already disposed";
        try {
            InteropLibrary.getFactory().getUncached().execute(disposeMokapotContext, vmPtr);
            this.vmPtr = 0L;
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere("Cannot dispose Espresso libjvm (mokapot).");
        }
        assert vmPtr == 0L;
    }

    @VmImpl
    public static long JVM_TotalMemory() {
        // TODO(peterssen): What to report here?
        return Runtime.getRuntime().totalMemory();
    }

    @VmImpl
    public static long JVM_MaxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    @VmImpl
    public static void JVM_GC() {
        System.gc();
    }

    @VmImpl
    public static void JVM_Halt(int code) {
        throw new EspressoExitException(code);
    }

    @VmImpl
    public static void JVM_Exit(int code) {
        // System.exit(code);
        // Unlike Halt, runs finalizers
        throw new EspressoExitException(code);
    }

    @VmImpl
    @JniImpl
    public @Host(Properties.class) StaticObject JVM_InitProperties(@Host(Properties.class) StaticObject properties) {
        Method setProperty = properties.getKlass().lookupMethod(Name.setProperty, Signature.Object_String_String);

        OptionValues options = getContext().getEnv().getOptions();

        // Set user-defined system properties.
        for (Map.Entry<String, String> entry : options.get(EspressoOptions.Properties).entrySet()) {
            setProperty.invokeWithConversions(properties, entry.getKey(), entry.getValue());
        }

        EspressoProperties props = getContext().getVmProperties();

        // Espresso uses VM properties, to ensure consistency the user-defined properties (that may
        // differ in some cases) are overwritten.
        setProperty.invokeWithConversions(properties, "java.class.path", Utils.stringify(props.classpath()));
        setProperty.invokeWithConversions(properties, "java.home", props.javaHome().toString());
        setProperty.invokeWithConversions(properties, "sun.boot.class.path", Utils.stringify(props.bootClasspath()));
        setProperty.invokeWithConversions(properties, "java.library.path", Utils.stringify(props.javaLibraryPath()));
        setProperty.invokeWithConversions(properties, "sun.boot.library.path", Utils.stringify(props.bootLibraryPath()));
        setProperty.invokeWithConversions(properties, "java.ext.dirs", Utils.stringify(props.extDirs()));

        // Set VM information.
        setProperty.invokeWithConversions(properties, "java.vm.specification.version", EspressoLanguage.VM_SPECIFICATION_VERSION);
        setProperty.invokeWithConversions(properties, "java.vm.specification.name", EspressoLanguage.VM_SPECIFICATION_NAME);
        setProperty.invokeWithConversions(properties, "java.vm.specification.vendor", EspressoLanguage.VM_SPECIFICATION_VENDOR);
        setProperty.invokeWithConversions(properties, "java.vm.version", EspressoLanguage.VM_VERSION);
        setProperty.invokeWithConversions(properties, "java.vm.name", EspressoLanguage.VM_NAME);
        setProperty.invokeWithConversions(properties, "java.vm.vendor", EspressoLanguage.VM_VENDOR);
        setProperty.invokeWithConversions(properties, "java.vm.info", EspressoLanguage.VM_INFO);

        return properties;
    }

    @VmImpl
    @JniImpl
    public int JVM_GetArrayLength(@Host(Object.class) StaticObject array) {
        try {
            return Array.getLength(MetaUtil.unwrapArrayOrNull(array));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw getMeta().throwExWithMessage(e.getClass(), e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    @VmImpl
    @JniImpl
    public static boolean JVM_DesiredAssertionStatus(@Host(Class.class) StaticObject unused, @Host(Class.class) StaticObject cls) {
        // TODO(peterssen): Assertions are always disabled, use the VM arguments.
        return false;
    }

    private static FrameInstance getCallerFrame(int depth, boolean securityStackWalk) {
        // TODO(peterssen): HotSpot verifies that the method is marked as @CallerSensitive.
        // Non-Espresso frames (e.g TruffleNFI) are ignored.
        // The call stack should look like this:
        // 2 : the @CallerSensitive annotated method.
        // ... : skipped non-Espresso frames.
        // 1 : getCallerClass method.
        // ... :
        // 0 : the callee.
        //
        // JVM_CALLER_DEPTH => the caller.
        int callerDepth = (depth == JVM_CALLER_DEPTH) ? 2 : depth + 1;

        final int[] depthCounter = new int[]{callerDepth};
        FrameInstance target = Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<FrameInstance>() {
                            @Override
                            public FrameInstance visitFrame(FrameInstance frameInstance) {
                                Method m = getMethodFromFrame(frameInstance);
                                if (m != null) {
                                    if (!securityStackWalk || !isIgnoredBySecurityStackWalk(m, m.getMeta())) {
                                        if (--depthCounter[0] < 0) {
                                            return frameInstance;
                                        }
                                    }
                                }
                                return null;
                            }
                        });
        if (target != null) {
            return target;
        }
        throw EspressoError.shouldNotReachHere();
    }

    private static EspressoRootNode getEspressoRootFromFrame(FrameInstance frameInstance) {
        if (frameInstance.getCallTarget() instanceof RootCallTarget) {
            RootCallTarget callTarget = (RootCallTarget) frameInstance.getCallTarget();
            RootNode rootNode = callTarget.getRootNode();
            if (rootNode instanceof EspressoRootNode) {
                return ((EspressoRootNode) rootNode);
            }
        }
        return null;
    }

    private static Method getMethodFromFrame(FrameInstance frameInstance) {
        EspressoRootNode root = getEspressoRootFromFrame(frameInstance);
        if (root != null) {
            return root.getMethod();
        }
        return null;
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_GetCallerClass(int depth) {
        // HotSpot comment:
        // Pre-JDK 8 and early builds of JDK 8 don't have a CallerSensitive annotation; or
        // sun.reflect.Reflection.getCallerClass with a depth parameter is provided
        // temporarily for existing code to use until a replacement API is defined.
        if (depth != JVM_CALLER_DEPTH) {
            FrameInstance callerFrame = getCallerFrame(depth, true);
            if (callerFrame != null) {
                Method callerMethod = getMethodFromFrame(callerFrame);
                if (callerMethod != null) {
                    return callerMethod.getDeclaringKlass().mirror();
                }
            }
            // Not found.
            return StaticObject.NULL;
        }

        // Getting the class of the caller frame.
        //
        // The call stack at this point looks something like this:
        //
        // [0] [ @CallerSensitive public sun.reflect.Reflection.getCallerClass ]
        // [1] [ @CallerSensitive API.method ]
        // [.] [ (skipped intermediate frames) ]
        // [n] [ caller ]
        Meta meta = getMeta();
        StaticObject[] exception = new StaticObject[]{null};
        Method callerMethod = Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<Method>() {
                            private int depth = 0;

                            @SuppressWarnings("fallthrough")
                            @Override
                            public Method visitFrame(FrameInstance frameInstance) {
                                Method method = getMethodFromFrame(frameInstance);
                                if (method != null) {
                                    switch (depth) {
                                        case 0:
                                            // This must only be called from
                                            // Reflection.getCallerClass.
                                            if (method != meta.sun_reflect_Reflection_getCallerClass) {
                                                exception[0] = meta.initExWithMessage(InternalError.class, "JVM_GetCallerClass must only be called from Reflection.getCallerClass");
                                                return /* ignore */ method;
                                            }
                                            // fall-through
                                        case 1:
                                            // Frame 0 and 1 must be caller sensitive.
                                            if ((method.getModifiers() & ACC_CALLER_SENSITIVE) == 0) {
                                                exception[0] = meta.initExWithMessage(InternalError.class, "CallerSensitive annotation expected at frame " + depth);
                                                return /* ignore */ method;
                                            }
                                            break;
                                        default:
                                            if (!isIgnoredBySecurityStackWalk(method, meta)) {
                                                return method;
                                            }
                                    }
                                    ++depth;
                                }
                                return null;
                            }
                        });

        // InternalError was recorded.
        StaticObject internalError = exception[0];
        if (internalError != null) {
            assert InterpreterToVM.instanceOf(internalError, meta.InternalError);
            throw new EspressoException(internalError);
        }

        if (callerMethod == null) {
            return StaticObject.NULL;
        }

        return callerMethod.getDeclaringKlass().mirror();
    }

    @VmImpl
    @JniImpl
    public @Host(Class[].class) StaticObject JVM_GetClassContext() {
        // TODO(garcia) This must only be called from SecurityManager.getClassContext
        ArrayList<StaticObject> result = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<Object>() {
                            @Override
                            public Object visitFrame(FrameInstance frameInstance) {
                                Method m = getMethodFromFrame(frameInstance);
                                if (m != null && !isIgnoredBySecurityStackWalk(m, getMeta()) && !m.isNative()) {
                                    result.add(m.getDeclaringKlass().mirror());
                                }
                                return null;
                            }
                        });
        return StaticObject.createArray(getMeta().Class_Array, result.toArray(StaticObject.EMPTY_ARRAY));
    }

    private static boolean isIgnoredBySecurityStackWalk(Method m, Meta meta) {
        Klass holderKlass = m.getDeclaringKlass();
        if (holderKlass == meta.Method && m.getName() == Name.invoke) {
            return true;
        }
        if (meta.MethodAccessorImpl.isAssignableFrom(holderKlass)) {
            return true;
        }
        if (MethodHandleIntrinsics.isMethodHandleIntrinsic(m, meta) || (m.getModifiers() & ACC_LAMBDA_FORM_COMPILED) != 0) {
            return true;
        }
        return false;
    }

    private boolean isAuthorized(StaticObject context, Klass klass) {
        if (!StaticObject.isNull(getMeta().System.getStatics().getField(getMeta().System_securityManager))) {
            if (getMeta().ProtectionDomain_impliesCreateAccessControlContext == null) {
                return true;
            }
            if ((boolean) getMeta().AccessControlContext_isAuthorized.invokeDirect(context)) {
                return true;
            }
            StaticObject pd = Target_java_lang_Class.getProtectionDomain0(klass.mirror());
            if (pd != StaticObject.NULL) {
                return (boolean) getMeta().ProtectionDomain_impliesCreateAccessControlContext.invokeDirect(pd);
            }
        }
        return true;
    }

    private @Host(AccessControlContext.class) StaticObject createACC(@Host(ProtectionDomain[].class) StaticObject context,
                    boolean isPriviledged,
                    @Host(AccessControlContext.class) StaticObject priviledgedContext) {
        Klass accKlass = getMeta().AccessControlContext;
        StaticObject acc = accKlass.allocateInstance();
        acc.setField(getMeta().ACC_context, context);
        acc.setField(getMeta().ACC_privilegedContext, priviledgedContext);
        acc.setBooleanField(getMeta().ACC_isPrivileged, isPriviledged);
        if (getMeta().ACC_isAuthorized != null) {
            acc.setBooleanField(getMeta().ACC_isAuthorized, true);
        }
        return acc;
    }

    private @Host(AccessControlContext.class) StaticObject createDummyACC() {
        Klass pdKlass = getMeta().ProtectionDomain;
        StaticObject pd = pdKlass.allocateInstance();
        getMeta().ProtectionDomain_init_CodeSource_PermissionCollection.invokeDirect(pd, StaticObject.NULL, StaticObject.NULL);
        StaticObject context = StaticObject.wrap(new StaticObject[]{pd});
        return createACC(context, false, StaticObject.NULL);
    }

    static private class PrivilegedStack {
        public static Supplier<PrivilegedStack> supplier = new Supplier<PrivilegedStack>() {
            @Override
            public PrivilegedStack get() {
                return new PrivilegedStack();
            }
        };

        private Element top;

        public void push(FrameInstance frame, StaticObject context, Klass klass) {
            top = new Element(frame, context, klass, top);
        }

        public void pop() {
            assert top != null : "poping empty privileged stack !";
            top = top.next;
        }

        public boolean compare(FrameInstance frame) {
            return top != null && top.compare(frame);
        }

        public StaticObject peekContext() {
            assert top != null;
            return top.context;
        }

        public StaticObject classLoader() {
            assert top != null;
            return top.klass.getDefiningClassLoader();
        }

        static private class Element {
            long frameID;
            StaticObject context;
            Klass klass;
            Element next;

            public Element(FrameInstance frame, StaticObject context, Klass klass, Element next) {
                this.frameID = initPrivilegedFrame(frame);
                this.context = context;
                this.klass = klass;
                this.next = next;
            }

            public boolean compare(FrameInstance other) {
                try {
                    FrameSlot slot = privilegedFrameSlots.get(getMethodFromFrame(other).identity());
                    return slot != null && other.getFrame(FrameInstance.FrameAccess.READ_ONLY).getLong(slot) == frameID;
                } catch (FrameSlotTypeException e) {
                    return false;
                }
            }

            // Dummy.
            private static final Object frameIdSlotIdentifier = new Object();

            private static final EconomicMap<Method, FrameSlot> privilegedFrameSlots = EconomicMap.create(Equivalence.IDENTITY);

            private static long newFrameID = 0L;

            /**
             * Injects the frame ID in the frame. Spawns a new frame slot in the frame descriptor of
             * the corresponding RootNode if needed.
             *
             * @param frame the current privileged frame.
             * @return the frame ID of the frame.
             */
            private static long initPrivilegedFrame(FrameInstance frame) {
                Method m = getMethodFromFrame(frame);
                FrameSlot slot = privilegedFrameSlots.get(m.identity());
                if (slot == null) {
                    slot = initSlot(frame, m);
                }
                assert slot == privilegedFrameSlots.get(m.identity());
                long id = ++newFrameID;
                frame.getFrame(FrameInstance.FrameAccess.READ_WRITE).setLong(slot, id);
                return id;
            }

            /**
             * Responsible for spawning the frame slot of root nodes that haven't yet been
             * encountered by JVM_doPrivileged.
             */
            private static FrameSlot initSlot(FrameInstance frame, Method m) {
                synchronized (privilegedFrameSlots) {
                    FrameSlot result = privilegedFrameSlots.get(m.identity());
                    if (result != null) {
                        return result;
                    }
                    result = getEspressoRootFromFrame(frame).getFrameDescriptor().addFrameSlot(frameIdSlotIdentifier, FrameSlotKind.Long);
                    privilegedFrameSlots.put(m, result);
                    return result;
                }
            }
        }
    }

    private static final ThreadLocal<PrivilegedStack> privilegedStackThreadLocal = ThreadLocal.withInitial(PrivilegedStack.supplier);

    @VmImpl
    @JniImpl
    @CompilerDirectives.TruffleBoundary
    @SuppressWarnings("unused")
    public @Host(Object.class) StaticObject JVM_DoPrivileged(@Host(Class.class) StaticObject cls,
                    @Host(typeName = "PrivilegedAction OR PrivilegedActionException") StaticObject action,
                    @Host(AccessControlContext.class) StaticObject context,
                    boolean wrapException) {
        if (StaticObject.isNull(action)) {
            throw getMeta().throwEx(NullPointerException.class);
        }
        FrameInstance callerFrame = getCallerFrame(0, false);
        assert callerFrame != null : "No caller ?";
        Klass caller = getMethodFromFrame(callerFrame).getDeclaringKlass();
        StaticObject acc = context;
        if (!StaticObject.isNull(context)) {
            if (!isAuthorized(context, caller)) {
                acc = createDummyACC();
            }
        }
        Method run = action.getKlass().lookupMethod(Name.run, Signature.Object);
        if (run == null || !run.isPublic() || run.isStatic()) {
            throw getMeta().throwEx(InternalError.class);
        }

        // Prepare the privileged stack
        PrivilegedStack stack = privilegedStackThreadLocal.get();
        stack.push(callerFrame, acc, caller);

        // Execute the action.
        StaticObject result = StaticObject.NULL;
        try {
            result = (StaticObject) run.invokeDirect(action);
        } catch (EspressoException e) {
            if (getMeta().Exception.isAssignableFrom(e.getExceptionObject().getKlass()) &&
                            !getMeta().RuntimeException.isAssignableFrom(e.getExceptionObject().getKlass())) {
                StaticObject wrapper = getMeta().PrivilegedActionException.allocateInstance();
                getMeta().PrivilegedActionException_init_Exception.invokeDirect(wrapper, e.getExceptionObject());
                throw new EspressoException(wrapper);
            }
            throw e;
        } finally {
            stack.pop();
        }
        return result;
    }

    @VmImpl
    @JniImpl
    @CompilerDirectives.TruffleBoundary
    @SuppressWarnings("unused")
    public @Host(Object.class) StaticObject JVM_GetStackAccessControlContext(@Host(Class.class) StaticObject cls) {
        ArrayList<StaticObject> domains = new ArrayList<>();
        final PrivilegedStack stack = privilegedStackThreadLocal.get();
        final boolean[] isPrivileged = new boolean[]{false};

        StaticObject context = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<StaticObject>() {
            StaticObject prevDomain = StaticObject.NULL;

            public StaticObject visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (stack.compare(frameInstance)) {
                        isPrivileged[0] = true;
                    }
                    StaticObject domain = Target_java_lang_Class.getProtectionDomain0(m.getDeclaringKlass().mirror());
                    if (domain != prevDomain && domain != StaticObject.NULL) {
                        domains.add(domain);
                        prevDomain = domain;
                    }
                    if (isPrivileged[0]) {
                        return stack.peekContext();
                    }
                }
                return null;
            }
        });

        if (domains.isEmpty()) {
            if (isPrivileged[0] && StaticObject.isNull(context)) {
                return StaticObject.NULL;
            }
            return createACC(StaticObject.NULL, isPrivileged[0], context == null ? StaticObject.NULL : context);
        }

        StaticObject guestContext = StaticObject.createArray(getMeta().ProtectionDomain.array(), domains.toArray(StaticObject.EMPTY_ARRAY));
        return createACC(guestContext, isPrivileged[0], context == null ? StaticObject.NULL : context);
    }

    @VmImpl
    @JniImpl
    @SuppressWarnings("unused")
    public @Host(Object.class) StaticObject JVM_GetInheritedAccessControlContext(@Host(Class.class) StaticObject cls) {
        return getContext().getCurrentThread().getField(getMeta().Thread_inheritedAccessControlContext);
    }

    @VmImpl
    @JniImpl
    public static @Host(Object.class) StaticObject JVM_LatestUserDefinedLoader() {
        StaticObject result = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<StaticObject>() {
            public StaticObject visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    Klass holder = m.getDeclaringKlass();
                    Meta meta = holder.getMeta();
                    // vfst.skip_reflection_related_frames(); // Only needed for 1.4 reflection
                    if (meta.MethodAccessorImpl.isAssignableFrom(holder) || meta.ConstructorAccessorImpl.isAssignableFrom(holder)) {
                        return null;
                    }

                    StaticObject loader = holder.getDefiningClassLoader();
                    // if (loader != NULL && !SystemDictionary::is_ext_class_loader(loader))
                    if (StaticObject.notNull(loader) && !Type.sun_misc_Launcher_ExtClassLoader.equals(loader.getKlass().getType())) {
                        return loader;
                    }
                }
                return null;
            }
        });

        return result == null ? StaticObject.NULL : result;
    }

    @VmImpl
    @JniImpl
    public static int JVM_GetClassAccessFlags(@Host(Class.class) StaticObject clazz) {
        Klass klass = clazz.getMirrorKlass();
        if (klass.isPrimitive()) {
            final int primitiveFlags = ACC_ABSTRACT | ACC_FINAL | ACC_PUBLIC;
            assert klass.getModifiers() == primitiveFlags;
            return klass.getModifiers();
        }
        return klass.getModifiers() & Constants.JVM_ACC_WRITTEN_FLAGS;
    }

    @VmImpl
    @JniImpl
    public static int JVM_GetClassModifiers(@Host(Class.class) StaticObject clazz) {
        Klass klass = clazz.getMirrorKlass();
        if (klass.isPrimitive()) {
            final int primitiveModifiers = ACC_ABSTRACT | ACC_FINAL | ACC_PUBLIC;
            assert klass.getClassModifiers() == primitiveModifiers;
            return klass.getClassModifiers();
        }
        return klass.getClassModifiers();
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_FindClassFromBootLoader(String name) {
        if (name == null) {
            return StaticObject.NULL;
        }

        String internalName = name;
        if (!name.startsWith("[")) {
            // Force 'L' type.
            internalName = "L" + name + ";";
        }

        if (!Validation.validTypeDescriptor(ByteSequence.create(internalName), false)) {
            return StaticObject.NULL;
        }

        Symbol<Type> type = getTypes().fromClassGetName(internalName);
        if (Types.isPrimitive(type)) {
            return StaticObject.NULL;
        }

        Klass klass = getRegistries().loadKlassWithBootClassLoader(type);
        if (klass == null) {
            return StaticObject.NULL;
        }

        return klass.mirror();
    }

    public TruffleObject getLibrary(@Word long handle) {
        return handle2Lib.get(handle);
    }

    public TruffleObject getFunction(@Word long handle) {
        return handle2Sym.get(handle);
    }

    /**
     * Returns the value of the indexed component in the specified array object. The value is
     * automatically wrapped in an object if it has a primitive type.
     *
     * @param array the array
     * @param index the index
     * @returns the (possibly wrapped) value of the indexed component in the specified array
     * @exception NullPointerException If the specified object is null
     * @exception IllegalArgumentException If the specified object is not an array
     * @exception ArrayIndexOutOfBoundsException If the specified {@code index} argument is
     *                negative, or if it is greater than or equal to the length of the specified
     *                array
     */
    @VmImpl
    @JniImpl
    public @Host(Object.class) StaticObject JVM_GetArrayElement(@Host(Object.class) StaticObject array, int index) {
        if (StaticObject.isNull(array)) {
            throw getMeta().throwEx(NullPointerException.class);
        }
        if (array.isArray()) {
            return getInterpreterToVM().getArrayObject(index, array);
        }
        if (!array.getClass().isArray()) {
            throw getMeta().throwExWithMessage(IllegalArgumentException.class, "Argument is not an array");
        }
        assert array.getClass().isArray() && array.getClass().getComponentType().isPrimitive();
        if (index < 0 || index >= JVM_GetArrayLength(array)) {
            throw getMeta().throwExWithMessage(ArrayIndexOutOfBoundsException.class, "index");
        }
        Object elem = Array.get(array, index);
        return guestBox(elem);
    }

    private static @Host(java.lang.reflect.Method.class) StaticObject getGuestReflectiveMethodRoot(@Host(java.lang.reflect.Method.class) StaticObject seed) {
        Meta meta = seed.getKlass().getMeta();
        assert InterpreterToVM.instanceOf(seed, meta.Method);
        StaticObject curMethod = seed;
        Method target = null;
        while (target == null) {
            target = (Method) curMethod.getHiddenField(meta.HIDDEN_METHOD_KEY);
            if (target == null) {
                curMethod = (StaticObject) meta.Method_root.get(curMethod);
            }
        }
        return curMethod;
    }

    private static @Host(java.lang.reflect.Field.class) StaticObject getGuestReflectiveFieldRoot(@Host(java.lang.reflect.Field.class) StaticObject seed) {
        Meta meta = seed.getKlass().getMeta();
        assert InterpreterToVM.instanceOf(seed, meta.Field);
        StaticObject curField = seed;
        Field target = null;
        while (target == null) {
            target = (Field) curField.getHiddenField(meta.HIDDEN_FIELD_KEY);
            if (target == null) {
                curField = (StaticObject) meta.Field_root.get(curField);
            }
        }
        return curField;
    }

    private static @Host(java.lang.reflect.Constructor.class) StaticObject getGuestReflectiveConstructorRoot(@Host(java.lang.reflect.Constructor.class) StaticObject seed) {
        Meta meta = seed.getKlass().getMeta();
        assert InterpreterToVM.instanceOf(seed, meta.Constructor);
        StaticObject curConstructor = seed;
        Method target = null;
        while (target == null) {
            target = (Method) curConstructor.getHiddenField(meta.HIDDEN_CONSTRUCTOR_KEY);
            if (target == null) {
                curConstructor = (StaticObject) meta.Constructor_root.get(curConstructor);
            }
        }
        return curConstructor;
    }

    @VmImpl
    @JniImpl
    public @Host(Parameter[].class) StaticObject JVM_GetMethodParameters(@Host(Object.class) StaticObject executable) {
        assert getMeta().Executable.isAssignableFrom(executable.getKlass());
        StaticObject parameterTypes = (StaticObject) executable.getKlass().lookupMethod(Name.getParameterTypes, Signature.Class_array).invokeDirect(executable);
        int numParams = parameterTypes.length();
        if (numParams == 0) {
            return StaticObject.NULL;
        }

        Method method;
        if (getMeta().Method.isAssignableFrom(executable.getKlass())) {
            method = Method.getHostReflectiveMethodRoot(executable);
        } else if (getMeta().Constructor.isAssignableFrom(executable.getKlass())) {
            method = Method.getHostReflectiveConstructorRoot(executable);
        } else {
            throw EspressoError.shouldNotReachHere();
        }

        MethodParametersAttribute methodParameters = (MethodParametersAttribute) method.getAttribute(Name.MethodParameters);

        if (methodParameters == null) {
            return StaticObject.NULL;
        }
        // Verify first.
        int cpLength = method.getConstantPool().length();
        for (MethodParametersAttribute.Entry entry : methodParameters.getEntries()) {
            int nameIndex = entry.getNameIndex();
            if (nameIndex < 0 || nameIndex >= cpLength) {
                throw getMeta().throwExWithMessage(getMeta().IllegalArgumentException,
                                getMeta().toGuestString("Constant pool index out of bounds"));
            }
            if (nameIndex != 0 && method.getConstantPool().tagAt(nameIndex) != ConstantPool.Tag.UTF8) {
                throw getMeta().throwExWithMessage(getMeta().IllegalArgumentException,
                                getMeta().toGuestString("Wrong type at constant pool index"));
            }
        }

        // TODO(peterssen): Cache guest j.l.reflect.Parameter constructor.
        // Calling the constructor is just for validation, manually setting the fields would
        // be faster.
        Method parameterInit = getMeta().Parameter.lookupDeclaredMethod(Name.INIT, getSignatures().makeRaw(Type._void,
                        /* name */ Type.String,
                        /* modifiers */ Type._int,
                        /* executable */ Type.Executable,
                        /* index */ Type._int));

        return getMeta().Parameter.allocateArray(numParams, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int index) {
                MethodParametersAttribute.Entry entry = methodParameters.getEntries()[index];
                StaticObject instance = getMeta().Parameter.allocateInstance();
                // For a 0 index, give an empty name.
                String hostName = "";
                if (entry.getNameIndex() != 0) {
                    hostName = method.getConstantPool().symbolAt(entry.getNameIndex(), "parameter name").toString();
                }
                parameterInit.invokeDirect(/* this */ instance,
                                /* name */ getMeta().toGuestString(hostName),
                                /* modifiers */ entry.getAccessFlags(),
                                /* executable */ executable,
                                /* index */ index);
                return instance;
            }
        });
    }

    @VmImpl
    @JniImpl
    public @Host(byte[].class) StaticObject JVM_GetMethodTypeAnnotations(@Host(java.lang.reflect.Executable.class) StaticObject guestReflectionMethod) {
        // guestReflectionMethod can be either a Method or a Constructor.
        if (InterpreterToVM.instanceOf(guestReflectionMethod, getMeta().Method)) {
            StaticObject methodRoot = getGuestReflectiveMethodRoot(guestReflectionMethod);
            assert methodRoot != null;
            return (StaticObject) methodRoot.getHiddenField(methodRoot.getKlass().getMeta().HIDDEN_METHOD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS);
        } else if (InterpreterToVM.instanceOf(guestReflectionMethod, getMeta().Constructor)) {
            StaticObject constructorRoot = getGuestReflectiveConstructorRoot(guestReflectionMethod);
            assert constructorRoot != null;
            return (StaticObject) constructorRoot.getHiddenField(constructorRoot.getKlass().getMeta().HIDDEN_CONSTRUCTOR_RUNTIME_VISIBLE_TYPE_ANNOTATIONS);
        } else {
            throw EspressoError.shouldNotReachHere();
        }
    }

    @VmImpl
    @JniImpl
    public @Host(byte[].class) StaticObject JVM_GetFieldTypeAnnotations(@Host(java.lang.reflect.Field.class) StaticObject guestReflectionField) {
        assert InterpreterToVM.instanceOf(guestReflectionField, getMeta().Field);
        StaticObject fieldRoot = getGuestReflectiveFieldRoot(guestReflectionField);
        assert fieldRoot != null;
        return (StaticObject) fieldRoot.getHiddenField(fieldRoot.getKlass().getMeta().HIDDEN_FIELD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS);
    }

    private StaticObject guestBox(Object elem) {
        if (elem instanceof Integer) {
            return (StaticObject) getMeta().Integer_valueOf.invokeDirect(null, (int) elem);
        }
        if (elem instanceof Boolean) {
            return (StaticObject) getMeta().Boolean_valueOf.invokeDirect(null, (boolean) elem);
        }
        if (elem instanceof Byte) {
            return (StaticObject) getMeta().Byte_valueOf.invokeDirect(null, (byte) elem);
        }
        if (elem instanceof Character) {
            return (StaticObject) getMeta().Character_valueOf.invokeDirect(null, (char) elem);
        }
        if (elem instanceof Short) {
            return (StaticObject) getMeta().Short_valueOf.invokeDirect(null, (short) elem);
        }
        if (elem instanceof Float) {
            return (StaticObject) getMeta().Float_valueOf.invokeDirect(null, (float) elem);
        }
        if (elem instanceof Double) {
            return (StaticObject) getMeta().Double_valueOf.invokeDirect(null, (double) elem);
        }
        if (elem instanceof Long) {
            return (StaticObject) getMeta().Long_valueOf.invokeDirect(null, (long) elem);
        }

        throw EspressoError.shouldNotReachHere("Not a boxed type " + elem);
    }

    @VmImpl
    @JniImpl
    public @Host(String.class) StaticObject JVM_GetSystemPackage(@Host(String.class) StaticObject name) {
        String hostPkgName = Meta.toHostString(name);
        if (hostPkgName.endsWith("/")) {
            hostPkgName = hostPkgName.substring(0, hostPkgName.length() - 1);
        }
        String fileName = getRegistries().getBootClassRegistry().getPackagePath(hostPkgName);
        return getMeta().toGuestString(fileName);
    }

    @VmImpl
    @JniImpl
    public @Host(String[].class) StaticObject JVM_GetSystemPackages() {
        String[] packages = getRegistries().getBootClassRegistry().getPackages();
        StaticObject[] array = new StaticObject[packages.length];
        for (int i = 0; i < packages.length; i++) {
            array[i] = getMeta().toGuestString(packages[i]);
        }
        StaticObject result = StaticObject.createArray(getMeta().String.getArrayClass(), array);
        return result;
    }

    @VmImpl
    public static long JVM_FreeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    /**
     * Espresso only supports basic -ea and -esa options. Complex per-class/package filters are
     * unsupported.
     */
    @VmImpl
    @JniImpl
    public @Host(typeName = "Ljava/lang/AssertionStatusDirectives;") StaticObject JVM_AssertionStatusDirectives(@SuppressWarnings("unused") @Host(Class.class) StaticObject unused) {
        Meta meta = getMeta();
        StaticObject instance = meta.AssertionStatusDirectives.allocateInstance();
        meta.AssertionStatusDirectives.lookupMethod(Name.INIT, Signature._void).invokeDirect(instance);
        meta.AssertionStatusDirectives_classes.set(instance, meta.String.allocateArray(0));
        meta.AssertionStatusDirectives_classEnabled.set(instance, meta._boolean.allocateArray(0));
        meta.AssertionStatusDirectives_packages.set(instance, meta.String.allocateArray(0));
        meta.AssertionStatusDirectives_packageEnabled.set(instance, meta._boolean.allocateArray(0));
        boolean ea = getContext().getEnv().getOptions().get(EspressoOptions.EnableAssertions);
        meta.AssertionStatusDirectives_deflt.set(instance, ea);
        return instance;
    }

    @VmImpl
    public static int JVM_ActiveProcessorCount() {
        return Runtime.getRuntime().availableProcessors();
    }


    @JniImpl
    @VmImpl
    public @Host(Class.class) StaticObject JVM_CurrentLoadedClass() {
        PrivilegedStack stack = privilegedStackThreadLocal.get();
        StaticObject mirrorKlass = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<StaticObject>() {
            public StaticObject visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (isTrustedFrame(frameInstance, stack)) {
                        return StaticObject.NULL;
                    }
                    if (!m.isNative()) {
                        ObjectKlass klass = m.getDeclaringKlass();
                        StaticObject loader = klass.getDefiningClassLoader();
                        if (StaticObject.notNull(loader) && !isTrustedLoader(loader)) {
                            return klass.mirror();
                        }
                    }
                }
                return null;
            }
        });
        return mirrorKlass == null ? StaticObject.NULL : mirrorKlass;
    }

    @JniImpl
    @VmImpl
    public @Host(Class.class) StaticObject JVM_CurrentClassLoader() {
        @Host(Class.class)
        StaticObject loadedClass = JVM_CurrentLoadedClass();
        return StaticObject.isNull(loadedClass) ? StaticObject.NULL : loadedClass.getMirrorKlass().getDefiningClassLoader();
    }

    @JniImpl
    @VmImpl
    public int JVM_ClassLoaderDepth() {
        PrivilegedStack stack = privilegedStackThreadLocal.get();
        Integer res = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Integer>() {
            int depth = 0;

            public Integer visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (isTrustedFrame(frameInstance, stack)) {
                        return -1;
                    }
                    if (!m.isNative()) {
                        ObjectKlass klass = m.getDeclaringKlass();
                        StaticObject loader = klass.getDefiningClassLoader();
                        if (StaticObject.notNull(loader) && !isTrustedLoader(loader)) {
                            return depth;
                        }
                        depth++;
                    }
                }
                return null;
            }
        });
        return res == null ? -1 : res;
    }

    @JniImpl
    @VmImpl
    public int JVM_ClassDepth(@Host(String.class) StaticObject name) {
        Symbol<Name> className = getContext().getNames().lookup(Meta.toHostString(name).replace('.', '/'));
        if (className == null) {
            return -1;
        }
        Integer res = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Integer>() {
            int depth = 0;

            public Integer visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (className.equals(m.getDeclaringKlass().getName())) {
                        return depth;
                    }
                    depth++;
                }
                return null;
            }
        });
        return res == null ? -1 : res;
    }

    private boolean isTrustedFrame(FrameInstance frameInstance, PrivilegedStack stack) {
        if (stack.compare(frameInstance)) {
            StaticObject loader = stack.classLoader();
            if (StaticObject.isNull(loader)) {
                return true;
            }
            if (isTrustedLoader(loader)) {
                return true;
            }
        }
        return false;
    }

    private StaticObject nonReflectionClassLoader(StaticObject loader) {
        if (StaticObject.notNull(loader)) {
            Meta meta = getMeta();
            if (meta.sun_reflect_DelegatingClassLoader.isAssignableFrom(loader.getKlass())) {
                return loader.getField(meta.ClassLoader_parent);
            }
        }
        return loader;
    }

    private boolean isTrustedLoader(StaticObject loader) {
        StaticObject nonDelLoader = nonReflectionClassLoader(loader);
        StaticObject systemLoader = (StaticObject) getMeta().ClassLoader_getSystemClassLoader.invokeDirect(null);
        while (StaticObject.notNull(systemLoader)) {
            if (systemLoader == nonDelLoader) {
                return true;
            }
            systemLoader = systemLoader.getField(getMeta().ClassLoader_parent);
        }
        return false;
    }

    // region Management

    // Checkstyle: stop
    // jmmLongAttribute;
    public static final int JMM_CLASS_LOADED_COUNT = 1; /* Total number of loaded classes */
    public static final int JMM_CLASS_UNLOADED_COUNT = 2; /* Total number of unloaded classes */
    public static final int JMM_THREAD_TOTAL_COUNT = 3; /*
                                                         * Total number of threads that have been
                                                         * started
                                                         */
    public static final int JMM_THREAD_LIVE_COUNT = 4; /* Current number of live threads */
    public static final int JMM_THREAD_PEAK_COUNT = 5; /* Peak number of live threads */
    public static final int JMM_THREAD_DAEMON_COUNT = 6; /* Current number of daemon threads */
    public static final int JMM_JVM_INIT_DONE_TIME_MS = 7; /*
                                                            * Time when the JVM finished
                                                            * initialization
                                                            */
    public static final int JMM_COMPILE_TOTAL_TIME_MS = 8; /*
                                                            * Total accumulated time spent in
                                                            * compilation
                                                            */
    public static final int JMM_GC_TIME_MS = 9; /* Total accumulated time spent in collection */
    public static final int JMM_GC_COUNT = 10; /* Total number of collections */
    public static final int JMM_JVM_UPTIME_MS = 11; /* The JVM uptime in milliseconds */
    public static final int JMM_INTERNAL_ATTRIBUTE_INDEX = 100;
    public static final int JMM_CLASS_LOADED_BYTES = 101; /*
                                                           * Number of bytes loaded instance classes
                                                           */
    public static final int JMM_CLASS_UNLOADED_BYTES = 102; /*
                                                             * Number of bytes unloaded instance
                                                             * classes
                                                             */
    public static final int JMM_TOTAL_CLASSLOAD_TIME_MS = 103; /*
                                                                * Accumulated VM class loader time
                                                                * (TraceClassLoadingTime)
                                                                */
    public static final int JMM_VM_GLOBAL_COUNT = 104; /* Number of VM internal flags */
    public static final int JMM_SAFEPOINT_COUNT = 105; /* Total number of safepoints */
    public static final int JMM_TOTAL_SAFEPOINTSYNC_TIME_MS = 106; /*
                                                                    * Accumulated time spent getting
                                                                    * to safepoints
                                                                    */
    public static final int JMM_TOTAL_STOPPED_TIME_MS = 107; /*
                                                              * Accumulated time spent at safepoints
                                                              */
    public static final int JMM_TOTAL_APP_TIME_MS = 108; /*
                                                          * Accumulated time spent in Java
                                                          * application
                                                          */
    public static final int JMM_VM_THREAD_COUNT = 109; /* Current number of VM internal threads */
    public static final int JMM_CLASS_INIT_TOTAL_COUNT = 110; /*
                                                               * Number of classes for which
                                                               * initializers were run
                                                               */
    public static final int JMM_CLASS_INIT_TOTAL_TIME_MS = 111; /*
                                                                 * Accumulated time spent in class
                                                                 * initializers
                                                                 */
    public static final int JMM_METHOD_DATA_SIZE_BYTES = 112; /* Size of method data in memory */
    public static final int JMM_CLASS_VERIFY_TOTAL_TIME_MS = 113; /*
                                                                   * Accumulated time spent in class
                                                                   * verifier
                                                                   */
    public static final int JMM_SHARED_CLASS_LOADED_COUNT = 114; /*
                                                                  * Number of shared classes loaded
                                                                  */
    public static final int JMM_SHARED_CLASS_UNLOADED_COUNT = 115; /*
                                                                    * Number of shared classes
                                                                    * unloaded
                                                                    */
    public static final int JMM_SHARED_CLASS_LOADED_BYTES = 116; /*
                                                                  * Number of bytes loaded shared
                                                                  * classes
                                                                  */
    public static final int JMM_SHARED_CLASS_UNLOADED_BYTES = 117; /*
                                                                    * Number of bytes unloaded
                                                                    * shared classes
                                                                    */
    public static final int JMM_OS_ATTRIBUTE_INDEX = 200;
    public static final int JMM_OS_PROCESS_ID = 201; /* Process id of the JVM */
    public static final int JMM_OS_MEM_TOTAL_PHYSICAL_BYTES = 202; /* Physical memory size */
    public static final int JMM_GC_EXT_ATTRIBUTE_INFO_SIZE = 401; /*
                                                                   * the size of the GC specific
                                                                   * attributes for a given GC
                                                                   * memory manager
                                                                   */

    // jmmBoolAttribute;
    public static final int JMM_VERBOSE_GC = 21;
    public static final int JMM_VERBOSE_CLASS = 22;
    public static final int JMM_THREAD_CONTENTION_MONITORING = 23;
    public static final int JMM_THREAD_CPU_TIME = 24;
    public static final int JMM_THREAD_ALLOCATED_MEMORY = 25;

    // Checkstyle: resume

    @JniImpl
    @VmImpl
    public synchronized long JVM_GetManagement(int version) {
        if (managementPtr == 0) {
            try {
                managementPtr = (long) InteropLibrary.getFactory().getUncached().execute(initializeManagementContext, lookupVmImplCallback);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                throw EspressoError.shouldNotReachHere(e);
            }
            assert this.managementPtr != 0;
        }
        return managementPtr;
    }

    final static int JMM_VERSION = 0x20010203;

    @JniImpl
    @VmImpl
    public int GetVersion() {
        return JMM_VERSION;
    }

    @JniImpl
    @VmImpl
    public int GetOptionalSupport(long /* jmmOptionalSupport **/ supportPtr) {
        if (supportPtr != 0L) {
            ByteBuffer supportBuf = directByteBuffer(supportPtr, 8);
            supportBuf.putInt(0); // nothing optional is supported
            return 0;
        }
        return -1;
    }

    /*
     * This is used by JDK 6 and earlier. For JDK 7 and after, use GetInputArgumentArray.
     */
    @JniImpl
    @VmImpl
    public Object GetInputArguments() {
        throw EspressoError.unimplemented("GetInputArguments");
    }

    @JniImpl
    @VmImpl
    public int GetThreadInfo(@Host(long[].class) StaticObject ids, int maxDepth, @Host(Object[].class) StaticObject infoArray) {

        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(String[].class) StaticObject GetInputArgumentArray() {
        return getMeta().String.allocateArray(0);
    }

    @JniImpl
    @VmImpl
    public @Host(Object[].class) StaticObject GetMemoryPools(Object mgr) {
        Klass memoryPoolMXBean = getMeta().loadKlass(Type.MemoryPoolMXBean, StaticObject.NULL);
        return memoryPoolMXBean.allocateArray(1, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int value) {
                // (String name, boolean isHeap, long uThreshold, long gcThreshold)
                return (StaticObject) getMeta().sun_management_ManagementFactory_createMemoryPool.invokeDirect(null,
                                /* String name */ getMeta().toGuestString("foo"),
                                /* boolean isHeap */ true,
                                /* long uThreshold */ -1L,
                                /* long gcThreshold */ 0L);
            }
        });
    }

    @JniImpl
    @VmImpl
    public @Host(Object[].class) StaticObject GetMemoryManagers(@Host(Object.class) StaticObject pool) {
        Klass memoryManagerMXBean = getMeta().loadKlass(Type.MemoryManagerMXBean, StaticObject.NULL);
        return memoryManagerMXBean.allocateArray(1, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int value) {
                // (String name, String type)
                return (StaticObject) getMeta().sun_management_ManagementFactory_createMemoryManager.invokeDirect(null,
                                /* String name */ getMeta().toGuestString("foo"),
                                /* String type */ StaticObject.NULL);
            }
        });
    }

    @JniImpl
    @VmImpl
    public @Host(Object.class) StaticObject GetMemoryPoolUsage(@Host(Object.class) StaticObject pool) {
        if (StaticObject.isNull(pool)) {
            return StaticObject.NULL;
        }
        Method init = getMeta().MemoryUsage.lookupDeclaredMethod(Symbol.Name.INIT, getSignatures().makeRaw(Type._void, Type._long, Type._long, Type._long, Type._long));
        StaticObject instance = getMeta().MemoryUsage.allocateInstance();
        init.invokeDirect(instance, 0L, 0L, 0L, 0L);
        return instance;
    }

    @JniImpl
    @VmImpl
    public @Host(Object.class) StaticObject GetPeakMemoryPoolUsage(@Host(Object.class) StaticObject pool) {
        if (StaticObject.isNull(pool)) {
            return StaticObject.NULL;
        }
        Method init = getMeta().MemoryUsage.lookupDeclaredMethod(Symbol.Name.INIT, getSignatures().makeRaw(Type._void, Type._long, Type._long, Type._long, Type._long));
        StaticObject instance = getMeta().MemoryUsage.allocateInstance();
        init.invokeDirect(instance, 0L, 0L, 0L, 0L);
        return instance;
    }

    @JniImpl
    @VmImpl
    public void GetThreadAllocatedMemory(@Host(long[].class) StaticObject ids, @Host(long[].class) StaticObject sizeArray) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(Object.class) StaticObject GetMemoryUsage(boolean heap) {
        Method init = getMeta().MemoryUsage.lookupDeclaredMethod(Symbol.Name.INIT, getSignatures().makeRaw(Type._void, Type._long, Type._long, Type._long, Type._long));
        StaticObject instance = getMeta().MemoryUsage.allocateInstance();
        init.invokeDirect(instance, 0L, 0L, 0L, 0L);
        return instance;
    }

    @JniImpl
    @VmImpl
    public long GetLongAttribute(@Host(Object.class) StaticObject obj, /* jmmLongAttribute */ int att) {
        switch (att) {
            case JMM_JVM_INIT_DONE_TIME_MS:
                return getContext().initVMDoneMs;
            case JMM_CLASS_LOADED_COUNT:
                return getRegistries().getLoadedClassesCount();
            case JMM_CLASS_UNLOADED_COUNT:
                return 0L;
            case JMM_JVM_UPTIME_MS:
                return System.currentTimeMillis() - getContext().initVMDoneMs;
            case JMM_OS_PROCESS_ID:
                String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                String[] parts = processName.split("@");
                return Long.parseLong(parts[0]);
        }
        throw EspressoError.unimplemented("GetLongAttribute " + att);
    }

    private boolean JMM_VERBOSE_GC_state = false;
    private boolean JMM_VERBOSE_CLASS_state = false;
    private boolean JMM_THREAD_CONTENTION_MONITORING_state = false;
    private boolean JMM_THREAD_CPU_TIME_state = false;
    private boolean JMM_THREAD_ALLOCATED_MEMORY_state = false;

    @JniImpl
    @VmImpl
    public boolean GetBoolAttribute(/* jmmBoolAttribute */ int att) {
        switch (att) {
            case JMM_VERBOSE_GC:
                return JMM_VERBOSE_GC_state;
            case JMM_VERBOSE_CLASS:
                return JMM_VERBOSE_CLASS_state;
            case JMM_THREAD_CONTENTION_MONITORING:
                return JMM_THREAD_CONTENTION_MONITORING_state;
            case JMM_THREAD_CPU_TIME:
                return JMM_THREAD_CPU_TIME_state;
            case JMM_THREAD_ALLOCATED_MEMORY:
                return JMM_THREAD_ALLOCATED_MEMORY_state;
        }
        throw EspressoError.unimplemented("GetBoolAttribute " + att);
    }

    @JniImpl
    @VmImpl
    public boolean SetBoolAttribute(/* jmmBoolAttribute */ int att, boolean flag) {
        switch (att) {
            case JMM_VERBOSE_GC:
                return JMM_VERBOSE_GC_state = flag;
            case JMM_VERBOSE_CLASS:
                return JMM_VERBOSE_CLASS_state = flag;
            case JMM_THREAD_CONTENTION_MONITORING:
                return JMM_THREAD_CONTENTION_MONITORING_state = flag;
            case JMM_THREAD_CPU_TIME:
                return JMM_THREAD_CPU_TIME_state = flag;
            case JMM_THREAD_ALLOCATED_MEMORY:
                return JMM_THREAD_ALLOCATED_MEMORY_state = flag;
        }
        throw EspressoError.unimplemented("SetBoolAttribute " + att);
    }

    @JniImpl
    @VmImpl
    public int GetLongAttributes(@Host(Object.class) StaticObject obj, /* jmmLongAttribute* */ long attsPtr, int count, /* long* */ long resultPtr) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(Object[].class) StaticObject FindCircularBlockedThreads() {
        throw EspressoError.unimplemented();
    }

    // Not used in JDK 6 or JDK 7
    @JniImpl
    @VmImpl
    public long GetThreadCpuTime(long thread_id) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(Object[].class) StaticObject GetVMGlobalNames() {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public int GetVMGlobals(@Host(Object[].class) StaticObject names, /* jmmVMGlobal* */ long globalsPtr, int count) {
        if (globalsPtr == 0L) {
            throw getMeta().throwEx(NullPointerException.class);
        }
        if (StaticObject.notNull(names)) {
            if (!names.getKlass().equals(getMeta().String.array())) {
                throw getMeta().throwExWithMessage(IllegalArgumentException.class, "Array element type is not String class");
            }

            StaticObject[] entries = names.unwrap();
            for (StaticObject entry : entries) {
                if (StaticObject.isNull(entry)) {
                    throw getMeta().throwEx(NullPointerException.class);
                }
                System.err.println("GetVMGlobals: " + Meta.toHostString(entry));
            }
        }
        return 0;
    }

    @JniImpl
    @VmImpl
    public int GetInternalThreadTimes(@Host(Object[].class) StaticObject names, @Host(long[].class) StaticObject times) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public boolean ResetStatistic( /* jvalue */ long obj, /* jmmStatisticType */ long type) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void SetPoolSensor(@Host(Object.class) StaticObject pool, /* jmmThresholdType */ long type, @Host(Object.class) StaticObject sensor) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public long SetPoolThreshold(@Host(Object.class) StaticObject pool, /* jmmThresholdType */ long type, long threshold) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(Object.class) StaticObject GetPoolCollectionUsage(@Host(Object.class) StaticObject pool) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public int GetGCExtAttributeInfo(@Host(Object.class) StaticObject mgr, /*
                                                                            * jmmExtAttributeInfo *
                                                                            */ long ext_infoPtr, int count) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void GetLastGCStat(@Host(Object.class) StaticObject mgr, /* jmmGCStat * */ long gc_statPtr) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public long GetThreadCpuTimeWithKind(long thread_id, boolean user_sys_cpu_time) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void GetThreadCpuTimesWithKind(@Host(long[].class) StaticObject ids, @Host(long[].class) StaticObject timeArray, boolean user_sys_cpu_time) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public int DumpHeap0(@Host(String.class) StaticObject outputfile, boolean live) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(Object[].class) StaticObject FindDeadlocks(boolean object_monitors_only) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void SetVMGlobal(@Host(String.class) StaticObject flag_name, /* jvalue */ long new_value) {
        throw EspressoError.unimplemented();
    }

    // void* reserved6;

    @JniImpl
    @VmImpl
    public @Host(Object[].class) StaticObject DumpThreads(@Host(long[].class) StaticObject ids, boolean lockedMonitors, boolean lockedSynchronizers) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void SetGCNotificationEnabled(@Host(Object.class) StaticObject mgr, boolean enabled) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(Object[].class) StaticObject GetDiagnosticCommands() {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void GetDiagnosticCommandInfo(@Host(Object[].class) StaticObject cmds, /* dcmdInfo * */ long infoArrayPtr) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void GetDiagnosticCommandArgumentsInfo(@Host(String.class) StaticObject commandName, long /* dcmdArgInfo* */ infoArrayPtr) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public @Host(String.class) StaticObject ExecuteDiagnosticCommand(@Host(String.class) StaticObject command) {
        throw EspressoError.unimplemented();
    }

    @JniImpl
    @VmImpl
    public void SetDiagnosticFrameworkNotificationEnabled(boolean enabled) {
        throw EspressoError.unimplemented();
    }

    // endregion Management

    // Checkstyle: resume method name check
}
