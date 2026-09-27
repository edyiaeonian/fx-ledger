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
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
    QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND),
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    QUOTE_ALREADY_USED(HttpStatus.CONFLICT),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT),
    QUOTE_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    SAME_ACCOUNT(HttpStatus.UNPROCESSABLE_CONTENT),
    // The fee would leave nothing to convert, or the result rounds to zero.
    AMOUNT_TOO_SMALL(HttpStatus.UNPROCESSABLE_CONTENT),
    // Another request holds a lock this one needs; retrying later is safe.
    LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    // No rates yet, or the latest are older than the configured maximum.
    RATES_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
