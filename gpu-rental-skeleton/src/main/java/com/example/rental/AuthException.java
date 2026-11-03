package com.example.rental;

import org.springframework.stereotype.Service;

/** 认证相关的业务异常，带 HTTP 状态码 */
public class AuthException extends RuntimeException {

    private final int status;

    public AuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
