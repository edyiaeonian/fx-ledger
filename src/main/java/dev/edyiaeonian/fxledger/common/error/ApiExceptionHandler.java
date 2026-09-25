package dev.edyiaeonian.fxledger.common.error;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 9457 problem+json response with a stable
 * {@code code}, so a client never has to parse an error message.
 *
 * <p>Spring's base class already maps its own exceptions (malformed JSON, an
 * unknown path, a wrong method) to problem details; this adds the code to
 * those and handles the application's own.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException exception) {
        return problem(exception.code(), exception.getMessage());
    }

    // A path variable of the wrong type, such as /customers/42 for a UUID.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException exception) {
        return problem(ErrorCode.MALFORMED_REQUEST, "invalid value for '" + exception.getName() + "'");
    }

    // lock_timeout expired while waiting for another transaction's lock.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail lockTimeout(PessimisticLockingFailureException exception) {
        log.warn("lock wait timed out", exception);
        return problem(ErrorCode.LOCK_TIMEOUT, "the account is busy; retry the request, with the same Idempotency-Key");
    }

    // The last resort. The details go to the log, never to the client: a
    // stack trace describes this program, and may reveal more than it should.
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("unhandled exception", exception);
        return problem(ErrorCode.INTERNAL_ERROR, "an unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "the request is invalid");
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    // Every other Spring-handled exception passes through here; give its
    // problem detail a code that matches its status.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (body instanceof ProblemDetail detail
                && (detail.getProperties() == null || !detail.getProperties().containsKey("code"))) {
            detail.setProperty("code", codeFor(status).name());
        }
        return super.handleExceptionInternal(exception, body, headers, status, request);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.is4xxClientError()) {
            return ErrorCode.MALFORMED_REQUEST;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setProperty("code", code.name());
        return problem;
    }
}
