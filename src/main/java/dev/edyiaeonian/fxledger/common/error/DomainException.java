package dev.edyiaeonian.fxledger.common.error;

/**
 * A request the application understood and refused: an unknown customer, a
 * duplicate account. Carries the code the API reports.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
