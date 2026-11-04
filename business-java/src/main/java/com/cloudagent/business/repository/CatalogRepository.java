package com.cloudagent.business.repository;

import com.cloudagent.business.entity.PlatformProduct;
import com.cloudagent.business.mapper.PlatformProductMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * CatalogService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC
 * 类型；参数数量固定，值不拼接进 SQL。
 */
@Repository
public class CatalogRepository {
  private final SqlExecutor s;
  private final PlatformProductMapper products;

  public CatalogRepository(SqlExecutor s, PlatformProductMapper products) {
    this.s = s;
    this.products = products;
  }

  /** 读取记录列表：listActiveSkus。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listActiveSkus(Object id) {
    return s.list(
        "SELECT *,capacity-reserved-allocated AS available FROM p0_skus WHERE product_id=? AND"
            + " active=TRUE",
        id);
  }

  /** 读取单条记录（不存在时抛出404）：lockAvailableSku。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockAvailableSku(Object id) {
    return s.one(
        "SELECT s.*,p.name AS product_name,p.category,p.active AS product_active FROM p0_skus s"
            + " JOIN p0_products p ON p.id=s.product_id WHERE s.id=? FOR UPDATE",
        id);
  }

  /** 读取记录列表：lockSku。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> lockSku(Object id) {
    return s.list("SELECT * FROM p0_skus WHERE id=? FOR UPDATE", id);
  }

  /** 读取聚合数值：countSkuOrders。锁和条件更新在调用方业务事务中生效。 */
  public long countSkuOrders(Object id) {
    return s.count("SELECT COUNT(*) FROM p0_orders WHERE sku_id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：saveSku。锁和条件更新在调用方业务事务中生效。 */
  public int saveSku(
      Object id,
      Object productId,
      Object name,
      Object region,
      Object zone,
      Object cpu,
      Object memoryGb,
      Object baseCents,
      Object diskCents,
      Object bandwidthCents,
      Object version,
      Object active,
      Object capacity,
      Object adapter,
      Object images,
      Object minDisk,
      Object maxDisk,
      Object maxBandwidth,
      Object periods,
      Object graceDays,
      Object refundDays) {
    return s.run(
        "INSERT INTO"
            + " p0_skus(id,product_id,name,region,zone,cpu,memory_gb,base_cents,disk_cents,bandwidth_cents,version,active,capacity,adapter,images,min_disk,max_disk,max_bandwidth,periods,grace_days,refund_days)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " product_id=VALUES(product_id),name=VALUES(name),region=VALUES(region),zone=VALUES(zone),cpu=VALUES(cpu),memory_gb=VALUES(memory_gb),base_cents=VALUES(base_cents),disk_cents=VALUES(disk_cents),bandwidth_cents=VALUES(bandwidth_cents),version=VALUES(version),active=VALUES(active),capacity=VALUES(capacity),adapter=VALUES(adapter),images=VALUES(images),min_disk=VALUES(min_disk),max_disk=VALUES(max_disk),max_bandwidth=VALUES(max_bandwidth),periods=VALUES(periods),grace_days=VALUES(grace_days),refund_days=VALUES(refund_days)",
        id,
        productId,
        name,
        region,
        zone,
        cpu,
        memoryGb,
        baseCents,
        diskCents,
        bandwidthCents,
        version,
        active,
        capacity,
        adapter,
        images,
        minDisk,
        maxDisk,
        maxBandwidth,
        periods,
        graceDays,
        refundDays);
  }

  /** 读取单条记录（不存在时抛出404）：findSku。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findSku(Object id) {
    return s.one("SELECT * FROM p0_skus WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：insertPriceVersion。锁和条件更新在调用方业务事务中生效。 */
  public int insertPriceVersion(
      Object id, Object skuId, Object version, Object snapshot, Object createdAt) {
    return s.run(
        "INSERT INTO p0_price_versions VALUES(?,?,?,?,?)", id, skuId, version, snapshot, createdAt);
  }

  /** 读取记录列表：listRegions。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listRegions() {
    return s.list("SELECT DISTINCT region,zone FROM p0_skus WHERE active=TRUE");
  }

  public PlatformProduct findProduct(String id) {
    return products.selectById(id);
  }

  public int insertProduct(PlatformProduct product) {
    return products.insert(product);
  }

  public int updateProduct(PlatformProduct product) {
    return products.updateById(product);
  }
}
