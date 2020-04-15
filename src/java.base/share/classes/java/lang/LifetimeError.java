package java.lang;

/**
 * TBD
 */
public class LifetimeError extends Error {
    static final long serialVersionUID = 1234L;

    /**
     * TBD
     * @param s String
     */
    public LifetimeError(String s) {
        super(s);
    }

    /**
     * TBD
     */
    public LifetimeError() {
        super();
    }
}