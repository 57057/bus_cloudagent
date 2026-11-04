package com.cloudagent.business.integration;

import java.time.Instant;
import java.util.Map;

/**
 * 资源供应商接口：创建、按业务号查询、状态操作和续费。 接口让 JobRunner 不直接依赖某个云厂商 SDK；当前注入的是模拟实现。
 * 业务号必须稳定，遇到超时先查询已有结果，不能简单换一个随机号再创建一台。
 */
public interface ResourceAdapter {
  /** 按业务号创建或返回已存在资源，要求重复调用可安全恢复。 */
  Map<String, Object> ensureCreated(String businessKey, Instant expires);

  /** 查询已有供应商结果，避免超时后盲目重复创建。 */
  Map<String, Object> query(String businessKey);

  /** 让供应商执行并确认目标状态，已释放资源不可复活。 */
  void state(String businessKey, String state);

  /** 让供应商确认新的到期时间，本地订单随后再提交履约结果。 */
  void renew(String businessKey, Instant expires);
}
