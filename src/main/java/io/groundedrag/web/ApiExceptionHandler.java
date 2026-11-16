package io.groundedrag.web;

import io.groundedrag.ingestion.IngestionBusyException;
import io.groundedrag.ingestion.UnsupportedDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problem details for every domain error, so clients get one error shape. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(UnsupportedDocumentException.class)
    ProblemDetail unsupported(UnsupportedDocumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(IngestionBusyException.class)
    ProblemDetail busy(IngestionBusyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
