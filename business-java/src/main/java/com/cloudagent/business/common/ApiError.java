package com.cloudagent.business.common;

/**
 * 业务主动拒绝请求时使用的运行时异常，类似 Python 中自定义的异常类。 status 表示 HTTP 状态码，code 是前端可判断的稳定错误码，message 给用户说明原因。
 * 它本身不会发送 HTTP 响应；ApiAdvice 或 ApiFilter 捕获后才转换成 JSON。
 */
public class ApiError extends RuntimeException {
  public final int status;
  public final String code;

  /** 构造业务异常，保存HTTP状态码和错误码，并把提示传给RuntimeException的父类构造器。 */
  public ApiError(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /** 条件为假时抛出 ApiError，类似 Python 的 if not condition: raise ...；它不是返回一个失败布尔值。 */
  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw new ApiError(status, code, message);
  }
}
