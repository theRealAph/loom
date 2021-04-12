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

import java.lang.reflect.Constructor;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

/**
 * Represents a scoped variable.
 *
 * <p> A scoped variable differs from a normal variable in that it is dynamically
 * scoped and intended for cases where context needs to be passed from a caller
 * to a transitive callee without using an explicit parameter. A scoped variable
 * does not have a default/initial value, it is bound, meaning it gets a value,
 * when executing an operation specified to {@link #runWithBinding(Object, Runnable)}
 * or {@link #callWithBinding(Object, Callable)}. Code executed by the operation
 * uses the {@link #get()} method to get the value of the variable. The variable reverts
 * to being unbound (or its previous value) when the operation completes.
 *
 * <p> Access to the value of a scoped variable is controlled by the accessibility
 * of the {@code ScopeLocal} object. A {@code ScopeLocal} object  will typically be declared
 * in a private static field so that it can only be accessed by code in that class
 * (or other classes within its nest).
 *
 * <p> ScopeLocal variables support nested bindings. If a scoped variable has a value
 * then the {@code runWithBinding} or {@code callWithBinding} can be invoked to run
 * another operation with a new value. Code executed by this methods "sees" the new
 * value of the variable. The variable reverts to its previous value when the
 * operation completes.
 *
 * <p> An <em>inheritable scoped variable</em> is created with the {@link
 * #inheritableForType(Class)} method and provides inheritance of values from
 * parent thread to child thread that is arranged when the child thread is
 * created. Unlike {@link InheritableThreadLocal}, inheritable scoped variable
 * are not copied into the child thread, instead the child thread will access
 * the same variable as the parent thread. The value of inheritable scoped
 * variables should be immutable to avoid needing synchronization to coordinate
 * access.
 *
 * <p> As an advanced feature, the {@link #snapshot()} method is defined to obtain
 * a {@link Snapshot} of the inheritable scoped variables that are currently bound.
 * This can be used to support cases where inheritance needs to be done at times
 * other than thread creation.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * @apiNote
 * The following example uses a scoped variable to make credentials available to callees.
 *
 * <pre>{@code
 *   private static final ScopeLocal<Credentials> CREDENTIALS = ScopeLocal.forType(Credentials.class);
 *
 *   Credentials creds = ...
 *   CREDENTIALS.runWithBinding(creds, () -> {
 *       :
 *       Connection connection = connectDatabase();
 *       :
 *   });
 *
 *   Connection connectDatabase() {
 *       Credentials credentials = CREDENTIALS.get();
 *       :
 *   }
 * }</pre>
 *
 * @param <T> the variable type
 * @since 99
 */
public final class ScopeLocal<T> {
    private final @Stable Class<? super T> type;
    private final @Stable int hash;

    // Is this scope-local value inheritable? We could handle this by
    // making ScopeLocal an abstract base class and scopeLocalBindings() a
    // virtual method, but that seems a little excessive.
    private final @Stable boolean isInheritable;

    public final int hashCode() { return hash; }

    /**
     * Represents a snapshot of inheritable scoped variables.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
     * or method in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 99
     * @see ScopeLocal#snapshot()
     */

    public static class Snapshot {
        final Snapshot prev;

        private static final Object NIL = new Object();

        Snapshot(Snapshot prev) {
            this.prev = prev;
        }

        Object find(ScopeLocal<?> key) {
            for (Snapshot b = this; b != null; b = b.prev) {
                if (b instanceof SingleBinding singlebinding) {
                    if (singlebinding.getKey() == key) {
                        return singlebinding.value;
                    }
                } else if (b instanceof MultiBinding split) {
                    if (((1 << Cache.primaryIndex(key)) & split.primaryBits) != 0) {
                        for (var binding = split.bindings;
                             binding != null;
                             binding = (SingleBinding) binding.prev) {
                            if (binding.getKey() == key) {
                                Object value = binding.get();
                                return value;
                            }
                        }
                    }
                } else {
                    throw new RuntimeException("impossible");
                }
            }
            return NIL;
        }

        /**
         * Runs an operation with this snapshot of inheritable scoped variables.
         *
         * @param op  the operation to run
         * @param <R> the type of the result of the function
         * @return the result
         * @throws Exception if the operation completes with an exception
         */
        final void runWithSnapshot(Runnable op) {
            ScopeLocal.runWithSnapshot(op, this);
        }

        /**
         * Runs a value-returning operation with this snapshot of inheritable
         * scoped variables.a
         *
         * @param op  the operation to run
         * @param <R> the type of the result of the function
         * @return the result
         * @throws Exception if the operation completes with an exception
         */
        <R> R callWithSnapshot(Callable<R> op) throws Exception {
            return ScopeLocal.callWithSnapshot(op, this);
        }
    }

    // An immutable object that represents the binding of a single value
    // to a single key.
    static final class SingleBinding extends Snapshot {
        final ScopeLocal<?> key;
        final Object value;

        SingleBinding(ScopeLocal<?> key, Object value, Snapshot prev) {
            super(prev);
            key.type.cast(value);
            this.key = key;
            this.value = value;
        }

        final Object get() {
            return value;
        }

        final ScopeLocal<?> getKey() {
            return key;
        }

    }

    // An immutable object that represents the binding of a number of
    // keys and values. The key-value bindings form a linked list.
    static final class MultiBinding extends Snapshot {
        final SingleBinding bindings;
        final short primaryBits;

        MultiBinding(SingleBinding bindings, Snapshot prev, short primaryBits) {
            super(prev);
            this.bindings = bindings;
            this.primaryBits = primaryBits;
        }
    }

    /**
     * TBD
     */
    public static final class BoundValues {
        // Bit masks: a 1 in postion n indicates that this set of bound values
        // hits that slot in the cache
        final short primaryBits, secondaryBits;
        final SingleBinding inheritables, nonInheritables;

       BoundValues(SingleBinding inheritables, SingleBinding nonInheritables, short primaryBits, short secondaryBits) {
            this.inheritables = inheritables;
            this.nonInheritables = nonInheritables;
            this.primaryBits = primaryBits;
            this.secondaryBits = secondaryBits;
        }

        /**
         * @param key   TBD
         * @param value TBD
         * @param <T>   TBD
         * @return TBD
         */
        public final <T> BoundValues where(ScopeLocal<T> key, T value) {
            var inheritables = this.inheritables;
            var nonInheritables = this.nonInheritables;
            if (key.isInheritable) {
                inheritables = new SingleBinding(key, value, inheritables);
            } else {
                nonInheritables = new SingleBinding(key, value, nonInheritables);
            }
            short primaryBits = (short)(this.primaryBits | (1 << Cache.primaryIndex(key)));
            short secondaryBits = (short)(this.secondaryBits | (1 << Cache.secondaryIndex(key)));
            return new BoundValues(inheritables, nonInheritables, primaryBits, secondaryBits);
        }

        /**
         * @param key   TBD
         * @param value TBD
         * @param <T>   TBD
         * @return TBD
         */
        static final <T> BoundValues of(ScopeLocal<T> key, T value) {
            SingleBinding inheritables = null;
            SingleBinding nonInheritables = null;
            if (key.isInheritable) {
                inheritables = new SingleBinding(key, value, inheritables);
            } else {
                nonInheritables = new SingleBinding(key, value, nonInheritables);
            }
            short primaryBits = (short)(1 << Cache.primaryIndex(key));
            short secondaryBits = (short)(1 << Cache.secondaryIndex(key));
            return new BoundValues(inheritables, nonInheritables, primaryBits, secondaryBits);
        }

        private final Snapshot setScopeLocalBindings(SingleBinding bindings, short primaryBits, boolean isInheritable) {
            Thread currentThread = Thread.currentThread();
            Snapshot prev;
            if (isInheritable) {
                prev = currentThread.inheritableScopeLocalBindings;
                if (bindings != null) {
                    var b = new MultiBinding(bindings, prev, primaryBits);
                    currentThread.inheritableScopeLocalBindings = b;
                }
            } else {
                prev = currentThread.noninheritableScopeLocalBindings;
                if (bindings != null) {
                    var b = new MultiBinding(bindings, prev, primaryBits);
                    currentThread.noninheritableScopeLocalBindings = b;
                }
            }
            return prev;
        }

        /**
         * Runs a value-returning operation with this some ScopeLocals bound to values.
         * Code executed by the operation can use the {@link #get()} method to
         * get the value of the variables. The variables revert to their previous values or
         * becomes {@linkplain #isBound() unbound} when the operation completes.
         *
         * @param op    the operation to run
         * @param <R>   the type of the result of the function
         * @return the result
         * @throws Exception if the operation completes with an exception
         */
        public final <R> R call(Callable<R> op) throws Exception {
            Objects.requireNonNull(op);
            Cache.invalidate(primaryBits | secondaryBits);
            var p1 = setScopeLocalBindings(this.inheritables, primaryBits,true);
            var p2 = setScopeLocalBindings(this.nonInheritables, primaryBits,false);
            try {
                return op.call();
            } finally {
                Thread currentThread = Thread.currentThread();
                currentThread.noninheritableScopeLocalBindings = p2;
                currentThread.inheritableScopeLocalBindings = p1;
                Cache.invalidate(primaryBits | secondaryBits);
            }
        }

        /**
         *
         * @param op the operation to run
         * @param <R> the type of the result of the function
         * @param <E> the type of the exception to throw
         * @param klass the type of the exception to throw
         * @return the result
         */
        public final <R, E extends RuntimeException> R callOrElseThrow(Callable<R> op, Class<E> klass) {
            try {
                return call(op);
            } catch (Exception cause) {
                try {
                    Constructor<E> constructor = klass.getConstructor(Throwable.class);
                    throw constructor.newInstance(cause);
                } catch (Exception lookupException) {
                    throw new RuntimeException(lookupException);
                }
            }
        }

        /**
         *
         * @param op the operation to run
         * @param handler a function to be applied if the operation completes with an exception
         * @param <R> the type of the result of the function
         * @return the result
         */
        public final <R> R callOrElze(Callable<R> op, Function<Exception, R> handler) {
            try {
                return call(op);
            } catch (Exception e) {
                return handler.apply(e);
            }
        }

        /**
         *
         * @param op the operation to run
         * @param handler a function to be applied if the operation completest with an exception
         * @param <R> the type of the result of the function
         * @return the result
         */
        public final <R> R callOrElse(Callable<R> op, Supplier<Function<Exception, R>> handler) {
            try {
                return call(op);
            } catch (Exception e) {
                return handler.get().apply(e);
            }
        }

        /**
         *
         * @param op the operation to run
         * @param handler a function to be applied if the operation completest with an exception
         * @param <R> the type of the result of the function
         * @return the result
         */
        public final <R> R callOrElse(Callable<R> op, Function<Exception, R> handler) {
            try {
                return call(op);
            } catch (Exception e) {
                return handler.apply(e);
            }
        }

        /**
         * Runs an operation with this some ScopeLocals bound to our values.
         * Code executed by the operation can use the {@link #get()} method to
         * get the value of the variables. The variables revert to their previous values or
         * becomes {@linkplain #isBound() unbound} when the operation completes.
         *
         * @param value the value for the variable, can be null
         * @param op    the operation to run
         * @throws Exception if the operation completes with an exception
         */
        public final void run(Runnable op) {
            Objects.requireNonNull(op);
            Cache.invalidate(primaryBits | secondaryBits);
            var p1 = setScopeLocalBindings(this.inheritables, primaryBits,true);
            var p2 = setScopeLocalBindings(this.nonInheritables, primaryBits,false);
            try {
                op.run();
            } finally {
                Thread currentThread = Thread.currentThread();
                currentThread.noninheritableScopeLocalBindings = p2;
                currentThread.inheritableScopeLocalBindings = p1;
                Cache.invalidate(primaryBits | secondaryBits);
            }
        }
    }


    /**
     *
     * @param key TBD
     * @param value TBD
     * @param <T> TBD
     * @return TBD
     */
    public static <T> BoundValues where(ScopeLocal<T> key, T value) {
        return BoundValues.of(key, value);
    }

    /**
     * Runs a value-returning operation with a snapshot of inheritable
     * scoped variables.
     *
     * @param op the operation to run
     * @param s the Snapshot. May be null.
     * @param <R> the type of the result of the function
     * @return the result
     * @throws Exception if the operation completes with an exception
     */
    public static <R> R callWithSnapshot(Callable<R> op, Snapshot s) throws Exception {
        var prev = Thread.currentThread().inheritableScopeLocalBindings;
        if (prev == s) {
            return op.call();
        }
        var cache = Thread.scopeLocalCache();
        Cache.invalidate();
        try {
            Thread.currentThread().inheritableScopeLocalBindings = s;
            return op.call();
        } finally {
            Thread.currentThread().inheritableScopeLocalBindings = prev;
            Thread.setScopeLocalCache(cache);
        }
    }

    /**
     * Runs an operation with this snapshot of inheritable scoped variables.
     *
     * @param op the operation to run
     * @param s the Snapshot. May be null.
     */
    public static void runWithSnapshot(Runnable op, Snapshot s) {
        var prev = Thread.currentThread().inheritableScopeLocalBindings;
        if (prev == s) {
            op.run();
            return;
        }
        var cache = Thread.scopeLocalCache();
        Cache.invalidate();
        try {
            Thread.currentThread().inheritableScopeLocalBindings = s;
            op.run();
        } finally {
            Thread.currentThread().inheritableScopeLocalBindings = prev;
            Thread.setScopeLocalCache(cache);
        }
    }

    /**
     * Return a Callable that captures this snapshot of inheritable
     * scoped variables.
     *
     * @param op the operation to run
     * @param <U> the type of the function
     * @return the new Callable
     */
    public static <U> Callable<U> inheritableAdapter(Callable<U> op) {
        var s = ScopeLocal.snapshot();
        return new Callable<U>() {
            @Override
            public U call() throws Exception {
                return callWithSnapshot(op, s);
            }
        };
    }

    private ScopeLocal(Class<? super T> type, boolean isInheritable) {
        this.type = Objects.requireNonNull(type);
        this.isInheritable = isInheritable;
        this.hash = generateKey();
    }

    /**
     * Creates a scoped variable to hold a value with the given type.
     *
     * @param <T> the type of the scoped variable's value.
     * @param <U> a supertype of {@code T}. It should either be {@code T} itself or, if T is a parameterized type, its generic type.
     * @param type The {@code Class} instance {@code T.class}
     * @return a scope variable
     */
    public static <U,T extends U> ScopeLocal<T> forType(Class<U> type) {
        return new ScopeLocal<T>(type, false);
    }

    /**
     * Creates an inheritable scoped variable to hold a value with the given type.
     *
     * @param <T> the type of the scoped variable's value.
     * @param <U> a supertype of {@code T}. It should either be {@code T} itself or, if T is a parameterized type, its generic type.
     * @param type The {@code Class} instance {@code T.class}
     * @return a scope variable
     */
    public static <U,T extends U> ScopeLocal<T> inheritableForType(Class<U> type) {
        return new ScopeLocal<T>(type, true);
    }

    private Snapshot scopeLocalBindings() {
        Thread currentThread = Thread.currentThread();
        return isInheritable
                ? currentThread.inheritableScopeLocalBindings
                : currentThread.noninheritableScopeLocalBindings;
    }

    private void setScopeLocalBindings(Snapshot bindings) {
        Thread currentThread = Thread.currentThread();
        if (isInheritable) {
            currentThread.inheritableScopeLocalBindings = bindings;
        } else {
            currentThread.noninheritableScopeLocalBindings = bindings;
        }
    }

    /**
     * Returns {@code true} if the variable is bound to a value.
     *
     * @return {@code true} if the variable is bound to a value, otherwise {@code false}
     */
    @SuppressWarnings("unchecked")
    public boolean isBound() {
        var bindings = scopeLocalBindings();
        if (bindings == null) {
            return false;
        }
        return (bindings.find(this) != Snapshot.NIL);
    }

    /**
     * Return the value of the variable or NIL if not bound.
     */
    private Object findBinding() {
        var bindings = scopeLocalBindings();
        if (bindings != null) {
            return bindings.find(this);
        } else {
            return Snapshot.NIL;
        }
    }

    /**
     * Return the value of the variable if bound, otherwise returns {@code other}.
     * @param other the value to return if not bound, can be {@code null}
     * @return the value of the variable if bound, otherwise {@code other}
     */
    public T orElse(T other) {
        Object obj = findBinding();
        if (obj != Snapshot.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            return other;
        }
    }

    /**
     * Return the value of the variable if bound, otherwise throws an exception
     * produced by the exception supplying function.
     * @param <X> Type of the exception to be thrown
     * @param exceptionSupplier the supplying function that produces an
     *        exception to be thrown
     * @return the value of the variable if bound
     * @throws X if the variable is unbound
     */
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        Objects.requireNonNull(exceptionSupplier);
        Object obj = findBinding();
        if (obj != Snapshot.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            throw exceptionSupplier.get();
        }
    }

    @SuppressWarnings("unchecked")
    private T slowGet() {
        var bindings = scopeLocalBindings();
        if (bindings == null)
            throw new NoSuchElementException();
        var value =  bindings.find(this);
        if (value == Snapshot.NIL) {
            throw new NoSuchElementException();
        }
        Cache.put(this, value);
        return (T)value;
    }

    /**
     * Returns the value of the variable.
     * @return the value of the variable
     * @throws NoSuchElementException if the variable is not bound (exception is TBD)
     */
    @ForceInline
    @SuppressWarnings("unchecked")
    public T get() {
        Object[] objects;
        if ((objects = Thread.scopeLocalCache()) != null) {
            // This code should perhaps be in class Cache. We do it
            // here because the generated code is small and fast and
            // we really want it to be inlined in the caller.
            int n = (hash & Cache.TABLE_MASK) * 2;
            if (objects[n] == this) {
                return (T)objects[n + 1];
            }
            n = ((hash >>> Cache.INDEX_BITS) & Cache.TABLE_MASK) * 2;
            if (objects[n] == this) {
                return (T)objects[n + 1];
            }
        }
        return slowGet();
    }

    /**
     * Runs an operation with this variable bound to the given value. Code
     * executed by the operation can use the {@link #get()} method to get the
     * value of the variable. The variable reverts to its previous value or
     * becomes {@linkplain #isBound() unbound} when the operation completes.
     *
     * @param value the value for the variable, can be null
     * @param op the operation to run
     */
    public void runWithBinding(T value, Runnable op) {
        Objects.requireNonNull(op);
        Snapshot top = scopeLocalBindings();
        Cache.update(this, value);
        try {
            setScopeLocalBindings(new SingleBinding(this, value, top));
            op.run();
        } finally {
            // assert(top == Thread.currentThread().scopeLocalBindings.prev);
            setScopeLocalBindings(top);
            Cache.remove(this);
        }
    }

    /**
     * Runs a value-returning operation with this variable bound to the given
     * value. Code executed by the operation can use the {@link #get()} method to
     * get the value of the variable. The variable reverts to its previous value or
     * becomes {@linkplain #isBound() unbound} when the operation completes.
     *
     * @param value the value for the variable, can be null
     * @param op the operation to run
     * @param <R> the type of the result of the function
     * @return the result
     * @throws Exception if the operation completes with an exception
     */
    public <R> R callWithBinding(T value, Callable<R> op) throws Exception {
        Objects.requireNonNull(op);
        Snapshot top = scopeLocalBindings();
        Cache.update(this, value);
        try {
            setScopeLocalBindings(new SingleBinding(this, value, top));
            return op.call();
        } finally {
            setScopeLocalBindings(top);
            Cache.remove(this);
        }
    }

    /**
     *
     * @param op TBD
     * @param keyA TBD
     * @param theA TBD
     * @param keyB TBD
     * @param theB TBD
     * @param keyC TBD
     * @param theC TBD
     * @param <R> TBD
     * @param <A> TBD
     * @param <B> TBD
     * @param <C> TBD
     * @return TBD
     * @throws Exception TBD
     */
    @ForceInline
    public static final <R, A, B, C> R callWith3Bindings(Callable<R> op,
                                            ScopeLocal<A> keyA, A theA,
                                            ScopeLocal<B> keyB, B theB,
                                            ScopeLocal<C> keyC, C theC) throws Exception {
        Object[] objects = Thread.scopeLocalCache();

        if (objects == null) {
            objects = new Object[Cache.TABLE_SIZE * 2];
            Thread.setScopeLocalCache(objects);
        }

        Snapshot top = Thread.currentThread().noninheritableScopeLocalBindings;

        final int pAi = Cache.primaryIndex(keyA), sAi = Cache.secondaryIndex(keyA),
                pBi = Cache.primaryIndex(keyB), sBi = Cache.secondaryIndex(keyB),
                pCi = Cache.primaryIndex(keyC), sCi = Cache.secondaryIndex(keyC);

        try {
            SingleBinding bindings = new SingleBinding(keyA, theA, null);
            bindings = new SingleBinding(keyB, theB, bindings);
            bindings = new SingleBinding(keyC, theC, bindings);

            int mask = (1 << pAi) | (1 << sAi) | (1 << pBi) | (1 << sBi) | (1 << pCi) | (1 << sCi);
            Thread.currentThread().noninheritableScopeLocalBindings = (new MultiBinding(bindings, top, (short) mask));
             if (objects != null) {
                 objects[pAi * 2] = objects[sAi * 2] = objects[pBi * 2] = objects[sBi * 2] = objects[pCi * 2] = objects[sCi * 2] = null;
             }

            return op.call();
        } finally {
            Thread.currentThread().noninheritableScopeLocalBindings = (top);
            if (objects != null) {
                objects[pAi * 2] = objects[sAi * 2] = objects[pBi * 2] = objects[sBi * 2] = objects[pCi * 2] = objects[sCi * 2] = null;
            }
        }
    }


    // A small fixed-size key-value cache. When a scope variable's get() method
    // is called, we record the result of the lookup in this per-thread cache
    // for fast access in future.
    private static class Cache {
        static final int INDEX_BITS = 4;  // Must be a power of 2
        static final int TABLE_SIZE = 1 << INDEX_BITS;
        static final int TABLE_MASK = TABLE_SIZE - 1;

        static final int primaryIndex(ScopeLocal<?> key) {
            return key.hash & TABLE_MASK;
        }

        static final int secondaryIndex(ScopeLocal<?> key) {
            return (key.hash >> INDEX_BITS) & TABLE_MASK;
        }

        static void put(ScopeLocal<?> key, Object value) {
            Object[] theCache = Thread.scopeLocalCache();
            if (theCache == null) {
                theCache = new Object[TABLE_SIZE * 2];
                Thread.setScopeLocalCache(theCache);
            }
            // Update the cache to replace one entry with the value we just looked up.
            // Each value can be in one of two possible places in the cache.
            // Pick a victim at (pseudo-)random.
            Thread thread = Thread.currentThread();
            int k1 = primaryIndex(key);
            int k2 = secondaryIndex(key);
            int tmp = chooseVictim(thread);
            int victim = tmp == 0 ? k1 : k2;
            int other = tmp == 0 ? k2 : k1;
            setKeyAndObjectAt(victim, key, value);
            if (getKey(theCache, other) == key) {
                setKey(theCache, other, null);
            }
        }

        private static final void update(Object key, Object value) {
            Object[] objects;
            if ((objects = Thread.scopeLocalCache()) != null) {
                int k1 = key.hashCode() & TABLE_MASK;
                if (getKey(objects, k1) == key) {
                    setKeyAndObjectAt(k1, key, value);
                }
                int k2 = (key.hashCode() >> INDEX_BITS) & TABLE_MASK;
                if (getKey(objects, k2) == key) {
                    setKeyAndObjectAt(k2, key, value);
                }
            }
        }

        private static final void remove(Object key) {
            Object[] objects;
            if ((objects = Thread.scopeLocalCache()) != null) {
                int k1 = key.hashCode() & TABLE_MASK;
                if (getKey(objects, k1) == key) {
                    setKeyAndObjectAt(k1, null, null);
                }
                int k2 = (key.hashCode() >> INDEX_BITS) & TABLE_MASK;
                if (getKey(objects, k2) == key) {
                    setKeyAndObjectAt(k2, null, null);
                }
            }
        }

        private static void setKeyAndObjectAt(int n, Object key, Object value) {
            Thread.scopeLocalCache()[n * 2] = key;
            Thread.scopeLocalCache()[n * 2 + 1] = value;
        }

        private static Object getKey(Object[] objs, int n) {
            return objs[n * 2];
        }

        private static void setKey(Object[] objs, int n, Object key) {
            objs[n * 2] = key;
        }

        // Return either 0 or 1, at pseudo-random. This chooses either the
        // primary or secondary cache slot.
        private static int chooseVictim(Thread thread) {
            int tmp = thread.victims;
            thread.victims = (tmp << 31) | (tmp >>> 1);
            return tmp & 1;
        }

        public static void invalidate() {
            Thread.setScopeLocalCache(null);
        }

        // Null a set of cache entries, indicated by the 1-bits given
        static void invalidate(int toClearBits) {
            assert(toClearBits == (short)toClearBits);
            Object[] objects;
            if ((objects = Thread.scopeLocalCache()) != null) {
                for (short bits = (short)toClearBits;
                     bits != 0; ) {
                    int index = Integer.numberOfTrailingZeros(bits);
                    setKey(objects, index, null);
                    bits &= ~1 << index;
                }
            }
        }
    }

    private static int nextKey = 0xf0f0_f0f0;

    // A Marsaglia xor-shift generator used to generate hashes. This one has full period, so
    // it generates 2**32 - 1 hashes before it repeats. We're going to use the lowest n bits
    // and the next n bits as cache indexes, so we make sure that those indexes are
    // different.
    private static synchronized int generateKey() {
        int x = nextKey;
        do {
            x ^= x >>> 12;
            x ^= x << 9;
            x ^= x >>> 23;
        } while ((x & Cache.TABLE_MASK)
                == ((x >>> Cache.INDEX_BITS) & Cache.TABLE_MASK));
        return (nextKey = x);
    }

    /**
     * Returns a "snapshot" of the inheritable scoped variables that are currently
     * bound.
     *
     * <p>This snapshot may be capured at any time. It is inteneded to be used
     * in circumstances where values may be shared by sub-tasks.
     *
     * @return a "snapshot" of the currently-bound inheritable scoped variables. May be null.
     */
    public static Snapshot snapshot() {
        return Thread.currentThread().inheritableScopeLocalBindings;
    }
}