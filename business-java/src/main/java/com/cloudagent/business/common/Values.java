package com.cloudagent.business.common;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 无状态值转换与输入验证；不访问数据库，也不依赖 Controller 或 Service。 */
public final class Values {
  private Values() {}

  /** 生成去掉连字符的UUID文本，用作应用分配的记录编号；它不是数据库自增主键。 */
  public static String id() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** 取得当前时刻并转成 JDBC Timestamp；系统按UTC语义存储时间。 */
  public static Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  /** 把指定Instant转成JDBC可以绑定的时间参数。 */
  public static Timestamp time(Instant value) {
    return Timestamp.from(value);
  }

  /** 把查询结果的Timestamp转回可做时间运算的Instant；依赖list方法前面的时间类型归一化。 */
  public static Instant instant(Object value) {
    return ((Timestamp) value).toInstant();
  }

  /** 把数据库/JSON中的Number取为long；调用者必须确保该字段存在且确实是数字。 */
  public static long num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  /** 兼容驱动返回的Boolean和0/1形式；这不是通用的任意字符串布尔解析器。 */
  public static boolean bool(Object value) {
    return value instanceof Boolean b
        ? b
        : value != null && Integer.parseInt(value.toString()) != 0;
  }

  /** 把字段转成字符串，null时返回空字符串；和Objects.toString(value)可能得到文本null不同。 */
  public static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  /** 要求非空白字符串并限制长度，最后去掉两端空白；不是对整个请求自动生成的DTO校验。 */
  public static String input(Map<String, Object> data, String key, int max) {
    Object v = data.get(key);
    ApiError.require(
        v instanceof String s && !s.isBlank() && s.length() <= max,
        400,
        "INVALID_INPUT",
        key + "不能为空且长度不能超过" + max);
    return v.toString().strip();
  }

  /** 使用BigDecimal.intValueExact拒绝小数和溢出，再检查业务上下限；不能简单强转把1.5截成1。 */
  public static int integer(Map<String, Object> data, String key, int min, int max) {
    try {
      int n = new java.math.BigDecimal(data.get(key).toString()).intValueExact();
      ApiError.require(n >= min && n <= max, 400, "INVALID_CONFIG", key + "超出范围");
      return n;
    } catch (NullPointerException | NumberFormatException | ArithmeticException e) {
      throw new ApiError(400, "INVALID_CONFIG", key + "必须为整数");
    }
  }
}
