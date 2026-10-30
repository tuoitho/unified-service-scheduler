package com.keyloop.scheduler.exception;

public class SchedulingConflictException extends RuntimeException {

    private final String errorCode;

    public SchedulingConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SchedulingConflictException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
