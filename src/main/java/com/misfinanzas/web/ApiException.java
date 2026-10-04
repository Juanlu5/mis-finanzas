package com.misfinanzas.web;

import org.springframework.http.HttpStatus;

/** Mirrors FastAPI's HTTPException so the (unchanged) frontend keeps reading errors as {"detail": message}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
