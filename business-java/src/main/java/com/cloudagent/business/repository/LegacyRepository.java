package com.cloudagent.business.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cloudagent.business.entity.*;
import com.cloudagent.business.mapper.*;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 历史表数据访问，仍保留旧投影和排序；当前 P0 工具不使用这些历史接口。 */
@Repository
public class LegacyRepository {
  private final CloudOrderMapper orders;
  private final CloudInstanceMapper instances;
  private final MetricsMapper metrics;

  public LegacyRepository(
      CloudOrderMapper orders, CloudInstanceMapper instances, MetricsMapper metrics) {
    this.orders = orders;
    this.instances = instances;
    this.metrics = metrics;
  }

  public List<Map<String, Object>> listOrders(String user, int limit) {
    return orders.selectMaps(
        new QueryWrapper<CloudOrder>()
            .select(
                "order_id",
                "product_name",
                "billing_mode",
                "amount",
                "status",
                "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at")
            .eq("user_id", user)
            .orderByDesc("created_at")
            .last("LIMIT " + limit));
  }

  public List<Map<String, Object>> listInstances(String user, int limit) {
    return instances.selectMaps(
        new QueryWrapper<CloudInstance>()
            .select("instance_id", "instance_type", "region_id", "zone_id", "public_ip", "status")
            .eq("user_id", user)
            .orderByDesc("instance_id")
            .last("LIMIT " + limit));
  }

  public long countOwnedInstance(String id, String user) {
    return instances.selectCount(
        new QueryWrapper<CloudInstance>().eq("instance_id", id).eq("user_id", user));
  }

  public Map<String, Object> aggregateMetrics(String id, String user) {
    return metrics.aggregate(id, user);
  }
}
