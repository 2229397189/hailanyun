package com.example.rental;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务异常统一返回 400 + 可读消息，避免前端拿到裸 500 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> auth(AuthException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @ExceptionHandler(RentalService.BizException.class)
    public ResponseEntity<Map<String, Object>> biz(RentalService.BizException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
