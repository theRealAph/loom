package java.lang;

/**
 * TBD
 * A Lifetime is a Thing.
 */
public class Lifetime {
    // v1:
    final Lifetime parent;

    // v2:
    final Thread thread;
    private int depth;

    ScopedMap scopedMap;

    final int index;

    private static boolean USE_CACHE = true;

    enum Version { V1, V2 }

    static final Version version = Version.V2;

    static private int counter;

    private Lifetime(Lifetime parent, Thread thread, int depth) {
        this.parent = parent;
        this.thread = thread;
        this.depth = depth;
        synchronized (Lifetime.class){
            index = counter++;
        }
    }

    // v1:
    private Lifetime(Lifetime parent) {
        this(parent, null, 0);
    }

    // v2:
    Lifetime(Thread thread, int depth) {
        this(null, thread, depth);
    }
    int depth() {
        return depth;
    }

    /**
     * TBD
     * @return Lifetime
     */
    static public Lifetime start() {
        if (version == Version.V1) {
            var t = Thread.currentThread();
            var lt = new Lifetime(t.currentLifetime());
            t.pushLifetime(lt);
            return lt;
        } else {
            return Thread.currentThread().pushLifetime();
        }
    }

    /**
     * TBD
     */
    public void close() {
        if (Lifetime.version == Lifetime.Version.V1) {
            Thread.currentThread().popLifetime(this);
        } else {
            thread.popLifetime(this);
            this.depth = Integer.MAX_VALUE;
        }
    }

    final ScopedMap scopedMap() {
        var map = scopedMapOrNull();
        if (map == null) {
            map = scopedMap = new ScopedMap();
        }
        return map;
    }

    final ScopedMap scopedMapOrNull() {
        if (version == Version.V1) {
            return scopedMap;
        } else {
            return thread.scopedMap();
        }
    }

    public String toString() {
        return "parent: " + parent + " index: " + index;
    }
}