package dev.edyiaeonian.fxledger.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every error the API can return, with its HTTP status.
 *
 * <p>The code is part of the API contract: clients branch on it, so a code is
 * never renamed, only added. The human-readable detail may change freely.
 */
public enum ErrorCode {
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
