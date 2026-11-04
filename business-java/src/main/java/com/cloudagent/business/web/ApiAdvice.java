package com.cloudagent.business.web;

import com.cloudagent.business.common.ApiError;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 控制器的统一异常出口。Controller/Service 抛出的异常在这里转换为统一 JSON。 {@code @RestControllerAdvice} 让异常处理作用于所有控制器；优先级
 * -1 用来尽早选择本处理器。 过滤器中发生的认证异常由 ApiFilter 自己处理，不能假定控制器异常处理器一定能接住。
 * 预期业务错误返回明确提示，未知错误只向浏览器提供请求号，完整堆栈保留在服务端。
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(-1)
public class ApiAdvice {
  /** 组合 code/message/data/requestId，并使用 ResponseEntity 指定 HTTP 状态码；Map.of 的键值均不能为 null。 */
  private ResponseEntity<?> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "data",
                Map.of(),
                "requestId",
                Objects.toString(MDC.get("requestId"), "")));
  }

  /** 保留业务异常指定的状态码和可读提示，例如库存不足返回409。 */
  @ExceptionHandler(ApiError.class)
  public ResponseEntity<?> domain(ApiError e) {
    return error(e.status, e.code, e.getMessage());
  }

  /** 把数据库唯一约束冲突转为409；先查询再插入并不能代替数据库唯一约束。 */
  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<?> duplicate(DuplicateKeyException e) {
    return error(409, "CONFLICT", "记录重复，请刷新后重试");
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  /** 处理无法解析的 JSON、参数类型/校验错误等请求问题。 */
  public ResponseEntity<?> invalid(Exception e) {
    return error(400, "INVALID_INPUT", "请求格式错误");
  }

  /** 处理请求尚未进入控制器就被 multipart 大小限制拦截的情况，返回413。 */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e) {
    return error(413, "ATTACHMENT_TOO_LARGE", "附件不能超过2MB");
  }

  /** 数据库访问失败时给出503，避免把连接信息和SQL异常详情发送到浏览器。 */
  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<?> database(DataAccessException e) {
    return error(503, "DATABASE_UNAVAILABLE", "数据库繁忙，请稍后重试");
  }

  /** 处理未预料的错误：服务端记录堆栈，客户端只取得通用提示和请求号。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> failed(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("API failure requestId={}", MDC.get("requestId"), e);
    return error(500, "INTERNAL_ERROR", "操作失败，请使用请求号联系管理员");
  }
}
