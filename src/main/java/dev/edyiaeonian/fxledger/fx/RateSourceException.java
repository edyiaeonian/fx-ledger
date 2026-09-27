package dev.edyiaeonian.fxledger.fx;

/** The rate source could not be reached, or answered with something unusable. */
public class RateSourceException extends RuntimeException {

    public RateSourceException(String message) {
        super(message);
    }

    public RateSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
