/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package org.graalvm.nativebridge;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

/**
 * Represents a single native image isolate. All {@link NativeObject}s have a {@link NativeIsolate}
 * context.
 */
public final class NativeIsolate {

    static final int CLOSED = -1;
    private static final long NULL = 0L;
    private static final Map<Long, NativeIsolate> isolates = new ConcurrentHashMap<>();
    private static final AtomicInteger UUIDS = new AtomicInteger(0);

    private final long uuid;
    private final long isolateId;
    private final JNIConfig config;
    private final Set<Cleaner> cleaners;
    private final ReferenceQueue<Object> cleanersQueue;
    private final ThreadLocal<NativeIsolateThread> attachedIsolateThread;
    private final Collection<NativeIsolateThread> threads;      // Guarded by this
    private volatile State state;  // Guarded by this

    private NativeIsolate(long isolateId, JNIConfig config) {
        if (isolateId == NULL) {
            throw new IllegalArgumentException("Isolate address must be non NULL");
        }
        this.uuid = UUIDS.incrementAndGet();
        this.isolateId = isolateId;
        this.config = config;
        this.cleaners = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.cleanersQueue = new ReferenceQueue<>();
        this.threads = new ArrayList<>();
        this.attachedIsolateThread = new ThreadLocal<>();
        this.state = State.ACTIVE;
    }

    /**
     * Binds a native image thread to this isolate. When a thread created in the native image enters
     * for the first time to the host, it must be registered to the {@link NativeIsolate} as a
     * native thread.
     *
     * @param isolateThreadId the isolate thread to bind.
     */
    public void registerNativeThread(long isolateThreadId) {
        NativeIsolateThread nativeIsolateThread = attachedIsolateThread.get();
        if (nativeIsolateThread != null) {
            throw new IllegalStateException(String.format("Native thread %s is already attached to isolate %s.", Thread.currentThread(), this));
        }
        synchronized (this) {
            if (!state.isValid()) {
                throw throwClosedException();
            }
            nativeIsolateThread = new NativeIsolateThread(Thread.currentThread(), this, true, isolateThreadId);
            threads.add(nativeIsolateThread);
            attachedIsolateThread.set(nativeIsolateThread);
        }
    }

    /**
     * Enters this {@link NativeIsolate} on the current thread.
     *
     * @throws IllegalStateException when this {@link NativeObject} is already closed or being
     *             closed.
     */
    public NativeIsolateThread enter() {
        NativeIsolateThread nativeIsolateThread = getOrCreateNativeIsolateThread();
        nativeIsolateThread.enter();
        return nativeIsolateThread;
    }

    /**
     * Returns true if the current thread is entered to this {@link NativeIsolate}.
     */
    public boolean isActive() {
        NativeIsolateThread nativeIsolateThread = attachedIsolateThread.get();
        return nativeIsolateThread != null && (nativeIsolateThread.isNativeThread() || nativeIsolateThread.isActive());
    }

    /**
     * Requests an isolate shutdown. If there is no host thread entered into this
     * {@link NativeIsolate} the isolate is closed and the isolate heap is freed. If this
     * {@link NativeIsolate} has active threads the isolate is freed by the last leaving thread.
     */
    public boolean shutdown() {
        NativeIsolateThread currentIsolateThread = attachedIsolateThread.get();
        if (currentIsolateThread != null && currentIsolateThread.isNativeThread()) {
            return false;
        }
        boolean deferredClose = false;
        synchronized (this) {
            if (state == State.DISPOSED) {
                return true;
            }
            state = State.DISPOSING;
            for (NativeIsolateThread nativeIsolateThread : threads) {
                deferredClose |= !nativeIsolateThread.invalidate();
            }
        }
        if (deferredClose) {
            return false;
        } else {
            return doIsolateShutdown();
        }
    }

    /**
     * Returns the isolate address.
     */
    public long getIsolateId() {
        return isolateId;
    }

    /**
     * Returns the {@link JNIConfig} used by this {@link NativeIsolate}.
     */
    public JNIConfig getConfig() {
        return config;
    }

    @Override
    public String toString() {
        return "NativeIsolate[" + uuid + " for 0x" + Long.toHexString(isolateId) + "]";
    }

    /**
     * Gets the NativeIsolate object for the entered isolate with the specified isolate address.
     * IMPORTANT: Must be used only when the isolate with the specified isolateId is entered.
     *
     * @param isolateId id of an entered isolate
     * @return NativeIsolate object for the entered isolate with the specified isolate address
     * @throws IllegalStateException when {@link NativeIsolate} does not exist for the
     *             {@code isolateId}
     */
    public static NativeIsolate get(long isolateId) {
        NativeIsolate res = isolates.get(isolateId);
        if (res == null) {
            throw new IllegalStateException("NativeIsolate for isolate 0x" + Long.toHexString(isolateId) + " does not exist.");
        }
        return res;
    }

    /**
     * Creates a {@link NativeIsolate} for the {@code isolateId} and {@link JNIConfig}. This method
     * can be called at most once, preferably right after creating the isolate. Use the
     * {@link #get(long)} method to get an existing {@link NativeIsolate} instance.
     *
     * @return the newly created {@link NativeIsolate} for the {@code isolateId}.
     * @throws IllegalStateException when {@link NativeIsolate} for the {@code isolateId} already
     *             exists.
     */
    public static NativeIsolate forIsolateId(long isolateId, JNIConfig config) {
        NativeIsolate res = new NativeIsolate(isolateId, config);
        NativeIsolate previous = isolates.put(isolateId, res);
        if (previous != null && previous.state != State.DISPOSED) {
            throw new IllegalStateException("NativeIsolate for isolate 0x" + Long.toHexString(isolateId) + " already exists and is not disposed.");
        }
        return res;
    }

    /**
     * Registers an object for cleanup. At some point after a {@code cleanableObject} is garbage
     * collected the {@link NativeIsolate} is entered and the {@code cleanupAction} is executed with
     * the isolate thread address parameter. The {@code cleanupAction} should perform cleanup in the
     * isolate heap. The {@code cleanupAction} returns {@code true} on success and {@code false} on
     * failure.
     */
    public void registerForCleanup(Object cleanableObject, LongPredicate cleanupAction) {
        if (state != State.DISPOSED) {
            cleanHandles();
            cleaners.add(new Cleaner(cleanersQueue, cleanableObject, cleanupAction));
        }
    }

    /*
     * Returns true if the isolate shutdown process has already begun or is finished.
     */
    public boolean isDisposed() {
        return state == State.DISPOSED;
    }

    void lastLeave() {
        synchronized (this) {
            for (NativeIsolateThread nativeIsolateThread : threads) {
                if (nativeIsolateThread.isActive()) {
                    return;
                }
            }
        }
        doIsolateShutdown();
    }

    RuntimeException throwClosedException() {
        throw new IllegalStateException("Isolate 0x" + Long.toHexString(getIsolateId()) + " is already closed.");
    }

    private void cleanHandles() {
        NativeIsolateThread nativeIsolateThread = null;
        Cleaner cleaner;
        try {
            while ((cleaner = (Cleaner) cleanersQueue.poll()) != null) {
                if (cleaners.remove(cleaner)) {
                    if (nativeIsolateThread == null) {
                        nativeIsolateThread = enter();
                    }
                    cleanImpl(this.isolateId, nativeIsolateThread.getIsolateThreadId(), cleaner.action);
                }
            }
        } finally {
            if (nativeIsolateThread != null) {
                nativeIsolateThread.leave();
            }
        }
    }

    private static void cleanImpl(long isolate, long isolateThread, LongPredicate action) {
        try {
            if (!action.test(isolateThread)) {
                throw new Exception(String.format("Error releasing %s in isolate 0x%x.", action, isolate));
            }
        } catch (Throwable t) {
            boolean ae = false;
            assert (ae = true) == true;
            if (ae) {
                t.printStackTrace();
            }
        }
    }

    private boolean doIsolateShutdown() {
        synchronized (this) {
            if (state == State.DISPOSED) {
                return true;
            }
            state = State.DISPOSED;
        }
        cleaners.clear();
        boolean success = false;

        NativeIsolateThread nativeIsolateThread = attachedIsolateThread.get();
        if (nativeIsolateThread == null) {
            nativeIsolateThread = new NativeIsolateThread(Thread.currentThread(), this, false, config.attachThread(isolateId));
            nativeIsolateThread.invalidate();
            attachedIsolateThread.set(nativeIsolateThread);
        }
        try {
            nativeIsolateThread.setShutDownRequest(true);
            try {
                success = config.shutDownIsolate(isolateId, nativeIsolateThread.isolateThread);
            } finally {
                nativeIsolateThread.setShutDownRequest(false);
            }
        } finally {
            if (success) {
                isolates.computeIfPresent(isolateId, (id, nativeIsolate) -> (nativeIsolate == NativeIsolate.this ? null : nativeIsolate));
            }
        }
        return success;
    }

    private NativeIsolateThread getOrCreateNativeIsolateThread() {
        NativeIsolateThread nativeIsolateThread = attachedIsolateThread.get();
        if (nativeIsolateThread == null) {
            synchronized (this) {
                if (!state.isValid()) {
                    throw throwClosedException();
                }
                nativeIsolateThread = new NativeIsolateThread(Thread.currentThread(), this, false, config.attachThread(isolateId));
                threads.add(nativeIsolateThread);
                attachedIsolateThread.set(nativeIsolateThread);
            }
        }
        return nativeIsolateThread;
    }

    private static final class Cleaner extends WeakReference<Object> {

        private final LongPredicate action;

        private Cleaner(ReferenceQueue<Object> cleanersQueue, Object referent, LongPredicate action) {
            super(referent, cleanersQueue);
            this.action = action;
        }
    }

    private enum State {

        ACTIVE(true),
        DISPOSING(false),
        DISPOSED(false);

        private final boolean valid;

        State(boolean valid) {
            this.valid = valid;
        }

        boolean isValid() {
            return valid;
        }
    }
}
