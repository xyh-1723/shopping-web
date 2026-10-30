package dev.learning.mall.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound(NoResourceFoundException e) {
        return ResponseEntity.notFound().build();
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            // 直接标在 @RequestParam 上的 @Min/@Max 走的是 Bean Validation，抛的是这个；
            // 不接住它就掉进下面的 Exception 兜底变成 500——客户端传错参数是 4xx，不是服务端出错。
            jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<?> validation(Exception e) {
        String message = "请求格式不正确，请检查必填字段、数量和请求头";
        if (e instanceof MethodArgumentNotValidException invalid) {
            message = invalid.getBindingResult().getAllErrors().getFirst().getDefaultMessage();
        } else if (e instanceof jakarta.validation.ConstraintViolationException violation) {
            message = violation.getConstraintViolations().stream()
                    .findFirst().map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .orElse(message);
        }
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", message));
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<?> busy(ConcurrencyFailureException e) {
        return ResponseEntity.status(409).body(Map.of("code", "BUSY", "message", "系统繁忙，请用原请求编号重试"));
    }
    /**
     * 改密时旧密码不对。{@code JdbcUserDetailsManager.changePassword} 抛这个异常，
     * 不接住就掉进兜底变 500——旧密码错是用户输入问题，该是 4xx。
     */
    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<?> badCredentials(org.springframework.security.authentication.BadCredentialsException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "OLD_PASSWORD_WRONG", "message", "旧密码不正确"));
    }
    /**
     * 助手线程池队列溢出。这是「服务端此刻没有容量」，不是「请求有问题」——
     * 必须如实返回 503（可重试），而不是被兜底成 500 让调用方以为代码出错了。
     */
    @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    public ResponseEntity<?> overloaded(org.springframework.core.task.TaskRejectedException e) {
        log.warn("助手线程池已满，请求被拒绝：{}", e.getMessage());
        return ResponseEntity.status(503)
                .body(Map.of("code", "ASSISTANT_BUSY", "message", "助手当前请求较多，请稍后再试。"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) {
        log.error("请求处理失败", e);
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR", "message", "服务器暂时无法处理请求"));
    }
}
