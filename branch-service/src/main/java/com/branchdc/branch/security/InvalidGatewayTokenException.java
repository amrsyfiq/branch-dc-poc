package com.branchdc.branch.security;

public class InvalidGatewayTokenException extends RuntimeException {

    public InvalidGatewayTokenException(String message) {
        super(message);
    }
}
