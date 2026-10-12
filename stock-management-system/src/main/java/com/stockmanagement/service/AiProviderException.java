package com.stockmanagement.service;

/** Provider call fail hone par; message user ko dikhaya ja sakta hai (key kabhi isme nahi hoti). */
public class AiProviderException extends RuntimeException {
    public AiProviderException(String message) {
        super(message);
    }
}