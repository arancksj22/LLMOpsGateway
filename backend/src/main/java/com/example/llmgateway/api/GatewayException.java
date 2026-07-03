package com.example.llmgateway.api;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String type;

    public GatewayException(HttpStatus status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }
}
