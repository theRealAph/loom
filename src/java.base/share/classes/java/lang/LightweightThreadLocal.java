/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

package java.lang;

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;
import java.util.function.Supplier;

import static java.lang.ScopedMap.NULL_PLACEHOLDER;

/**
 * This class extends {@code ThreadLocal} to provide inheritance of
 * values from parent thread to child thread: when a thread is started
 * by a bounded construct such as an ExecutorService or a parallel
 * stream, the thread executing the task is passed a reference to the
 * parent's set of LightweightThreadLocals. While the thread is
 * running the parent thread's set of LightweightThreadLocals is
 * immutable: any attempt to modify it returns a LifetimeError. The
 * child thread has its own set of LightweightThreadLocals.
 *
 */

public class LightweightThreadLocal<T> extends ThreadLocal<T> {

    @Stable
    private final int hash = ScopedMap.generateKey();

    @Stable
    private final Class<T> theType;

    /**
     * TBD
     *
     * @return TBD
     */
    final Class<T> getType() {
        return theType;
    }

    @ForceInline
    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private static final Object getObject(int hash, LightweightThreadLocal<?> key) {
        Object[] objects;
        if ((objects = Thread.scopedCache()) != null) {
            // This code should perhaps be in class ScopedCache. We do
            // it here because the generated code is small and fast
            // and we really want it to be inlined in the caller.
            int n = (hash & ScopedCache.TABLE_MASK) * 2;
            if (objects[n] == key) {
                return objects[n + 1];
            }
            n = ((hash >>> ScopedCache.INDEX_BITS) & ScopedCache.TABLE_MASK) * 2;
            if (objects[n] == key) {
                return objects[n + 1];
            }
        }
        return key.slowGet(Thread.currentThread());
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     * If the current thread does not support thread locals then
     * this method returns its {@link #initialValue} (or {@code null}
     * if the {@code initialValue} method is not overridden).
     *
     * @return the current thread's value of this thread-local
     * @see Thread.Builder#disallowThreadLocals()
     */
    @Override
    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    public T get() {
        return (T)getObject(hashCode(), this);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.
     *
     * @since 1.5
     */
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private T slowGet(Thread thread) {
        Lifetime currentLifetime = thread.currentLifetime();

        var value = NULL_PLACEHOLDER;

        for (var t = thread; t != null; t = t.parentThread) {
            var map = t.scopedMapOrNull();
            if (map == null) continue;
            value = map.get(hashCode(), this);
            if (value != NULL_PLACEHOLDER) break;
        }

        if (value == NULL_PLACEHOLDER)
            value = initialValue();

        ScopedCache.put(thread, this, value);

        return (T) value;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     *
     * @throws UnsupportedOperationException if the current thread does not
     *         support thread locals
     *
     * @see Thread.Builder#disallowThreadLocals()
     */
    @Override
    public void set(T value) {
        throw new UnsupportedOperationException();
    }

    /**
     * TBD
     *
     * @param t     TBD
     * @param chain TBD
     * @return TBD
     */
    @Override
    @SuppressWarnings(value = {"unchecked", "rawtypes"})
    // one map has entries for all types <T>
    public ScopedBinding bind(T t) {
        if (t != null && ! theType.isInstance(t))
            throw new ClassCastException(ScopedBinding.cannotBindMsg(t, theType));
        var lifetime = Lifetime.start();
        var map = Thread.currentThread().scopedMap();
        Object previousMapping = map.put(hashCode(), this, t);

        var b = new ScopedBinding(this, t, previousMapping, lifetime);

        ScopedCache.update(this, t);

        return b;
    }

    /**
     * Creates an inheritable thread local variable.
     */
    private LightweightThreadLocal() {
        theType = null;
    }

    LightweightThreadLocal(Class<T> klass) {
        theType = klass;
    }

    final void release(Object prev) {
        var map = Thread.currentThread().scopedMap();
        if (prev != NULL_PLACEHOLDER) {
            map.put(hashCode(), this, prev);
        } else {
            map.remove(hashCode(), this);
        }
        ScopedCache.remove(this);
    }
}