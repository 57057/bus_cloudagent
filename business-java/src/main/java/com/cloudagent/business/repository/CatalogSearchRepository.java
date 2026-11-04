package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/** 公开商品检索；只接收业务层已验证的筛选值，SQL 结构在数据访问层构建。 */
@Repository
public class CatalogSearchRepository {
  private final SqlExecutor s;

  public CatalogSearchRepository(SqlExecutor s) {
    this.s = s;
  }

  public Object products(Map<String, String> q, int page, int size) {
    StringBuilder where = new StringBuilder(" WHERE p.active=TRUE");
    List<Object> args = new ArrayList<>();
    if (!q.getOrDefault("keyword", "").isBlank()) {
      where.append(" AND (p.name LIKE ? OR p.description LIKE ?)");
      args.add("%" + q.get("keyword") + "%");
      args.add("%" + q.get("keyword") + "%");
    }
    if (!q.getOrDefault("category", "").isBlank()) {
      where.append(" AND p.category=?");
      args.add(q.get("category"));
    }
    StringBuilder sk = new StringBuilder("s.product_id=p.id AND s.active=TRUE");
    for (String key : List.of("region", "cpu", "memory_gb", "min_price", "max_price"))
      if (!q.getOrDefault(key, "").isBlank()) {
        sk.append(
            switch (key) {
              case "min_price" -> " AND s.base_cents>=?";
              case "max_price" -> " AND s.base_cents<=?";
              default -> " AND s." + key + "=?";
            });
        args.add(key.equals("region") ? q.get(key) : Integer.parseInt(q.get(key)));
      }
    if (args.size()
        > (q.containsKey("keyword") && !q.get("keyword").isBlank() ? 2 : 0)
            + (q.containsKey("category") && !q.get("category").isBlank() ? 1 : 0))
      where.append(" AND EXISTS(SELECT 1 FROM p0_skus s WHERE ").append(sk).append(")");
    long total = s.count("SELECT COUNT(*) FROM p0_products p" + where, args.toArray());
    String order =
        switch (q.getOrDefault("sort", "name")) {
          case "price_asc" -> "price_cents ASC";
          case "price_desc" -> "price_cents DESC";
          default -> "p.name ASC";
        };
    List<Object> paged = new ArrayList<>(args);
    paged.add(size);
    paged.add((page - 1) * size);
    var rows =
        s.list(
            "SELECT p.*, (SELECT MIN(base_cents+min_disk*disk_cents+bandwidth_cents) FROM p0_skus s"
                + " WHERE s.product_id=p.id AND s.active=TRUE) AS price_cents, EXISTS(SELECT 1 FROM"
                + " p0_skus s WHERE s.product_id=p.id AND s.active=TRUE AND s.adapter='SIMULATED'"
                + " AND p.category IN ('ECS','GPU','RDS','DISK')) AS purchasable FROM p0_products p"
                + where
                + " ORDER BY "
                + order
                + " LIMIT ? OFFSET ?",
            paged.toArray());
    return Map.of("items", rows, "total", total, "page", page, "size", size);
  }
}
