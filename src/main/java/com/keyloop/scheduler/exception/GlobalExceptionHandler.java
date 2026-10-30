package com.keyloop.scheduler.exception;

import com.keyloop.scheduler.observability.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Resource Not Found",
                ex.getMessage(),
                ex.getErrorCode());
    }

    @ExceptionHandler(SchedulingConflictException.class)
    public ProblemDetail handleConflict(SchedulingConflictException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Scheduling Conflict",
                ex.getMessage(),
                ex.getErrorCode());
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ProblemDetail handleBusinessValidation(BusinessValidationException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Business Rule Violation",
                ex.getMessage(),
                ex.getErrorCode());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Invalid Request Parameters",
                ex.getMessage(),
                "VALIDATION_FAILED");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ProblemDetail problem = buildProblem(
                HttpStatus.BAD_REQUEST,
                "Invalid Request Payload",
                "One or more request fields failed validation",
                "VALIDATION_FAILED");
        problem.setProperty("fieldErrors", fieldErrors);
        return createResponseEntity(problem, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception processing request", ex);
        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected internal error occurred",
                "INTERNAL_ERROR");
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (body instanceof ProblemDetail problem) {
            Map<String, Object> props = problem.getProperties();
            if (props == null || !props.containsKey("errorCode")) {
                String fallbackCode = statusCode.value() == HttpStatus.BAD_REQUEST.value()
                        ? "MALFORMED_REQUEST"
                        : "HTTP_" + statusCode.value();
                problem.setProperty("errorCode", fallbackCode);
                problem.setType(URI.create("https://api.keyloop.example/problems/" + fallbackCode.toLowerCase()));
            }
            enrichMetadata(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ProblemDetail buildProblem(
            HttpStatus status,
            String title,
            String detail,
            String errorCode
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://api.keyloop.example/problems/" + errorCode.toLowerCase()));
        problem.setProperty("errorCode", errorCode);
        enrichMetadata(problem);
        return problem;
    }

    private void enrichMetadata(ProblemDetail problem) {
        Map<String, Object> props = problem.getProperties();
        if (props == null || !props.containsKey("timestamp")) {
            problem.setProperty("timestamp", Instant.now(clock).toString());
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null && (props == null || !props.containsKey("correlationId"))) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
