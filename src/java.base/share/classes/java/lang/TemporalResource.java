package java.lang;

/**
 * TBD
 * A TemporalResource is a Thing.
 */
public abstract class TemporalResource implements AutoCloseable {
    private final Lifetime lt;

   /**
     * TBD
     */
    protected TemporalResource() { lt = Lifetime.start(); }

   /**
     * TBD
     */
    protected void checkAccess() {
        if (lt == null || Scoped.Cache.isActive(lt)) return;
        if (!Thread.currentThread().isActive(lt))
            throw new LifetimeError();
    }

   /**
     * TBD
     */
     public void close() { lt.close(); }
}
