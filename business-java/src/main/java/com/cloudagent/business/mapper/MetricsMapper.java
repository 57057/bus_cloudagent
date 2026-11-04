package com.cloudagent.business.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.*;

/**
 * 旧版每日监控表的聚合查询 Mapper，演示自定义参数化 SQL。 #{instanceId}/#{userId} 是 MyBatis 参数绑定，@Param 声明绑定名称，不是手工拼接
 * SQL。 统计含今天在内的近七个日期；CPU/内存使用平均值，网络字段使用每日最大值中的最大值。 SQL 中虽然有 network_out_bandwidth_mbps
 * 别名，它并不是七天网络均值。
 */
public interface MetricsMapper {
  /** 参数名由@Param绑定到SQL的#{...}，避免把实例ID拼进SQL正文。 返回Map字段days_count=0时，旧服务会判定没有监控样本。 */
  @Select(
      "SELECT ROUND(AVG(avg_cpu_usage_percent),2) AS cpu_usage_percent,"
          + " ROUND(AVG(avg_memory_usage_percent),2) AS memory_usage_percent,"
          + " ROUND(MAX(max_network_out_mbps),2) AS network_out_bandwidth_mbps, COUNT(*) AS"
          + " days_count FROM instance_metrics_daily WHERE instance_id=#{instanceId} AND"
          + " user_id=#{userId} AND metric_date >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)")
  Map<String, Object> aggregate(
      @Param("instanceId") String instanceId, @Param("userId") String userId);
}
