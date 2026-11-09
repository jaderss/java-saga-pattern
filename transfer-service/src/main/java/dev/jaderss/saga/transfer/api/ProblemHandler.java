package dev.jaderss.saga.transfer.api;

import dev.jaderss.saga.transfer.app.IdempotencyConflictException;
import dev.jaderss.saga.transfer.domain.NotAwaitingApprovalException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProblemHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail conflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail concurrentDuplicate() {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "a transfer with this Idempotency-Key is being created, retry the request");
    }

    @ExceptionHandler(NotAwaitingApprovalException.class)
    ProblemDetail notAwaiting(NotAwaitingApprovalException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
