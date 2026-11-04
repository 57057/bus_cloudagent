package com.cloudagent.business.repository;

import com.cloudagent.business.common.ApiError;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据访问层内部 JDBC 执行器。业务层不能提交 SQL；只调用具名 Repository/Mapper 方法。 同一数据源参与 Spring 业务事务；统一 JDBC 时间语义和参数绑定。
 */
@Component
public class SqlExecutor {
  private final JdbcTemplate db;

  public SqlExecutor(JdbcTemplate db) {
    this.db = db;
  }

  /** 执行查询并取第一行；没有记录抛404业务异常，而不是像 Python fetchone 一样返回None。它不检查是否意外返回了多行。 */
  public Map<String, Object> one(String sql, Object... args) {
    var rows = list(sql, args);
    if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "记录不存在或无权访问");
    return rows.get(0);
  }

  /** 执行参数化查询并返回行列表；将MySQL驱动返回的 LocalDateTime 统一解释为UTC Timestamp，便于后续计时。 */
  public List<Map<String, Object>> list(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    for (var row : rows)
      row.replaceAll(
          (key, value) ->
              value instanceof LocalDateTime local
                  ? Timestamp.from(local.toInstant(ZoneOffset.UTC))
                  : value);
    return rows;
  }

  /** 执行INSERT/UPDATE/DELETE并返回受影响行数。业务常用返回值==1判断是否抢到库存或完成预期状态转换。 */
  public int run(String sql, Object... args) {
    return db.update(sql, args);
  }

  /** 读取SQL返回的第一个Long标量；除COUNT外，也用于COALESCE(SUM(...),0)这样的非空数值查询。 */
  public long count(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }
}
