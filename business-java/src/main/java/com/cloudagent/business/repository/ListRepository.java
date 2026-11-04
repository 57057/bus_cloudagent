package com.cloudagent.business.repository;

import static com.cloudagent.business.common.ApiError.require;

import java.util.*;
import org.springframework.stereotype.Repository;

/** 受控列表的数据访问；动态表名、投影和排序均由固定白名单决定，值始终绑定。 */
@Repository
public class ListRepository {
  private final SqlExecutor s;

  public ListRepository(SqlExecutor s) {
    this.s = s;
  }

  public Object page(String type, String user, Map<String, String> q, int page, int size) {
    require(
        Set.of(
                "orders",
                "instances",
                "payments",
                "refunds",
                "tickets",
                "notifications",
                "products",
                "skus",
                "tasks",
                "outbox",
                "audits",
                "users",
                "price_versions",
                "renewals",
                "legacy_orders",
                "payment_events",
                "notification_templates")
            .contains(type),
        404,
        "NOT_FOUND",
        "不存在的资源类型");
    List<Object> args = new ArrayList<>();
    String where = " WHERE 1=1";
    if (user != null) {
      where += " AND user_id=?";
      args.add(user);
    }
    if (!q.getOrDefault("state", "").isBlank()
        && Set.of("orders", "instances", "payments", "refunds", "tickets", "tasks", "outbox")
            .contains(type)) {
      where += " AND state=?";
      args.add(q.get("state"));
    }
    long total = s.count("SELECT COUNT(*) FROM p0_" + type + where, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    String columns =
        type.equals("users")
            ? "id,username,nickname,avatar_id,role,enabled,preferences_enabled,created_at"
            : "*";
    String sort =
        Set.of(
                    "products",
                    "skus",
                    "outbox",
                    "price_versions",
                    "renewals",
                    "legacy_orders",
                    "payment_events",
                    "notification_templates")
                .contains(type)
            ? (type.equals("renewals") ? "order_id" : "id")
            : "created_at DESC,id";
    return Map.of(
        "items",
        s.list(
            "SELECT "
                + columns
                + " FROM p0_"
                + type
                + where
                + " ORDER BY "
                + sort
                + " LIMIT ? OFFSET ?",
            args.toArray()),
        "total",
        total,
        "page",
        page,
        "size",
        size);
  }
}
