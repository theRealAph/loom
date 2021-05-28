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

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import static jdk.internal.javac.PreviewFeature.Feature.SCOPE_LOCALS;

/**
 * Represents a scoped variable.
 *
 * @param <T> the variable type
 * @since 99
 */
@jdk.internal.javac.PreviewFeature(feature=SCOPE_LOCALS)
public abstract class AbstractScopeLocal<T> {

    /**
     * For subclasses.
     */
    protected AbstractScopeLocal() { }

    /**
     * Creates a binding for a ScopeLocal instance.
     * That {@link Carrier} may be used later to invoke a {@link Callable} or
     * {@link Runnable} instance. More bindings may be added to the {@link Carrier}
     * by the {@link Carrier#where(ScopeLocal, Object)} method.
     *
     * @param value The value to bind it to
     * @param <T> the type of the ScopeLocal
     * @param prev the previous value in the carrier chain
     * @return A Carrier instance that contains one binding, that of key and value
     */
    protected abstract ScopeLocal.Carrier bind(T value, ScopeLocal.Carrier prev);

    /**
     * A convenience method for AbstractScopeLocal classes. Given an existing
     * Carrier list (may be null), add a new key - value binding.
     * @param key an AbstractScopeLocal.
     * @param value the value to bind to it.
     * @param prev an already-bound list of scope locals. May be null.
     * @param <T> the type of the scope local.
     * @return a new Carrier list.
     */
    protected static <T> ScopeLocal.Carrier bind(AbstractScopeLocal<T> key, T value, ScopeLocal.Carrier prev) {
        if (prev != null) {
            return prev.where(key, value);
        } else {
            return ScopeLocal.where(key, value);
        }
    }
}