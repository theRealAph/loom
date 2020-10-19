/*
 * Copyright (c) 2019, Red Hat, Inc. and/or its affiliates. All rights reserved.
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

import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;
import jdk.internal.vm.annotation.ForceInline;
import sun.security.action.GetPropertyAction;

import static jdk.internal.misc.UnsafeConstants.SCOPED_CACHE_SHIFT;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static java.lang.ScopedMap.NULL_PLACEHOLDER;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.Arrays;

class ScopedCache {

    static final int INDEX_BITS = SCOPED_CACHE_SHIFT;

    static final int TABLE_SIZE = 1 << INDEX_BITS;

    static final int TABLE_MASK = TABLE_SIZE - 1;

    static Object[] createCache() {
        Object[] objects = new Object[TABLE_SIZE * 2 + 2];
        Thread.setScopedCache(objects);  // 2 extra slots for lifetimes
        return objects;
    }

    static void put(Thread t, Object key, Object value) {
        if (Thread.scopedCache() == null) {
            createCache();
        }
        setKeyAndObjectAt(chooseVictim(t, key.hashCode()), key, value);
    }

    static final void update(Object key, Object value) {
        Object[] objects;
        if ((objects = Thread.scopedCache()) != null) {

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

    static final void remove(Object key) {
        Object[] objects;
        if ((objects = Thread.scopedCache()) != null) {

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
        Thread.scopedCache()[n * 2] = key;
        Thread.scopedCache()[n * 2 + 1] = value;
    }

    private static Object getKey(Object[] objs, long hash) {
        int n = (int) (hash & TABLE_MASK);
        return objs[n * 2];
    }

    private static void setKey(Object[] objs, long hash, Object key) {
        int n = (int) (hash & TABLE_MASK);
        objs[n * 2] = key;
    }

    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private static final Object getKey(int n) {
        return Thread.scopedCache()[n * 2];
    }

    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private static final Object getObject(int n) {
        return Thread.scopedCache()[n * 2 + 1];
    }

    private static int chooseVictim(Thread thread, int hash) {
        // Update the cache to replace one entry with the value we just looked up.
        // Each value can be in one of two possible places in the cache.
        // Pick a victim at (pseudo-)random.
        int k1 = hash & TABLE_MASK;
        int k2 = (hash >> INDEX_BITS) & TABLE_MASK;
        int tmp = thread.victims;
        thread.victims = (tmp << 31) | (tmp >>> 1);
        return (chooseVictim(thread) & 1) == 0 ? k1 : k2;
    }

    private static int chooseVictim(Thread thread) {
        // Update the cache to replace one entry with the value we just looked up.
        // Each value can be in one of two possible places in the cache.
        // Pick a victim at (pseudo-)random.
        int tmp = thread.victims;
        thread.victims = (tmp << 31) | (tmp >>> 1);
        return tmp & TABLE_MASK;
    }

    static void clearCache() {
        // We need to do this when we yield a Continuation.
        Object[] objects = Thread.scopedCache();
        if (objects != null) {
            Arrays.fill(objects, null);
        }
    }
}



