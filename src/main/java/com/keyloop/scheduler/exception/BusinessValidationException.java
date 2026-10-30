package com.keyloop.scheduler.exception;

public class BusinessValidationException extends RuntimeException {

    private final String errorCode;

    public BusinessValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
