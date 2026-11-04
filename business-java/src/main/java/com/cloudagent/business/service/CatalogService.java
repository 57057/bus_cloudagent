package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.entity.PlatformProduct;
import com.cloudagent.business.repository.CatalogRepository;
import com.cloudagent.business.repository.CatalogSearchRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产品目录、可售配置与价格规则的业务服务。 SPU 是“ECS 云服务器”这样的产品，SKU 是指定地域、CPU、内存等的一套可售规格。
 * 公开查询与管理员维护在同一服务中复用规则；管理方法仍必须校验角色和记录审计。 价格以整数分存储和计算，商品修改不会覆盖旧订单中的价格/规则快照。
 */
@Service
public class CatalogService {
  private final JsonCodec json;
  private final BusinessEvents events;

  private final CatalogRepository repository;
  private final CatalogSearchRepository search;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public CatalogService(
      JsonCodec json,
      BusinessEvents events,
      CatalogRepository repository,
      CatalogSearchRepository search) {
    this.json = json;
    this.events = events;

    this.repository = repository;
    this.search = search;
  }

  /** 动态组合受控筛选条件，先统计总数再分页查询；搜索值使用?绑定，排序只能从固定选项选择。 */
  public Object products(Map<String, String> q) {
    int page = parameter(q, "page", 1, 1, 100000), size = parameter(q, "size", 12, 1, 100);
    Map<String, String> validated = new HashMap<>(q);
    for (String key : List.of("cpu", "memory_gb", "min_price", "max_price"))
      if (!q.getOrDefault(key, "").isBlank())
        validated.put(key, Integer.toString(parameter(q, key, 0, 0, 100000000)));
    return search.products(validated, page, size);
  }

  /** 解析查询字符串中的整数参数；缺省/空值使用默认值，非法数值由integer抛业务异常。 */
  public static int parameter(Map<String, String> q, String key, int fallback, int min, int max) {
    if (!q.containsKey(key) || q.get(key).isBlank()) return fallback;
    return integer(Map.of(key, q.get(key)), key, min, max);
  }

  /** 通过MyBatis-Plus查询已上架产品；下架产品在公开接口中按不存在处理。 */
  public Object product(String id) {
    var p = repository.findProduct(id);
    require(p != null && Boolean.TRUE.equals(p.active), 404, "NOT_FOUND", "商品不存在或已下架");
    return p;
  }

  /** 先确认产品公开可见，再列出其启用SKU，并计算每个SKU的可售库存。 */
  public Object skus(String id) {
    product(id);
    return repository.listActiveSkus(id);
  }

  /** 查询并锁定SKU及关联产品，检查上架、ECS类型和模拟供应能力；应由报价/下单的事务方法调用以持有锁。 */
  public Map<String, Object> available(String id) {
    var sku = repository.lockAvailableSku(id);
    require(
        bool(sku.get("active"))
            && bool(sku.get("product_active"))
            && Set.of("ECS", "GPU", "RDS", "DISK").contains(text(sku, "category"))
            && text(sku, "adapter").equals("SIMULATED"),
        409,
        "SKU_UNAVAILABLE",
        "当前SKU不可购买");
    return sku;
  }

  /** 验证镜像、ESSD类型、磁盘容量、带宽和实例名；不能因为前端下拉框有限就省略服务端校验。 */
  public void validateConfig(Map<String, Object> sku, Map<String, Object> config) {
    require(
        Arrays.asList(text(sku, "images").split(",")).contains(input(config, "image", 40)),
        400,
        "INVALID_CONFIG",
        "不支持的镜像");
    require(input(config, "disk_type", 20).equals("ESSD"), 400, "INVALID_CONFIG", "首版支持ESSD系统盘");
    integer(config, "disk_gb", (int) num(sku, "min_disk"), (int) num(sku, "max_disk"));
    integer(config, "bandwidth", 1, (int) num(sku, "max_bandwidth"));
    input(config, "name", 80);
  }

  /** 计算一台实例整个租期的费用：基础月价+磁盘月价+带宽月价，再乘月数和折扣。用整数分及明确舍入规则避免浮点结算误差。 */
  public static long unitPrice(Map<String, Object> sku, Map<String, Object> config, int months) {
    long monthly =
        Math.addExact(
            num(sku, "base_cents"),
            Math.addExact(
                Math.multiplyExact(num(sku, "disk_cents"), integer(config, "disk_gb", 1, 100000)),
                Math.multiplyExact(
                    num(sku, "bandwidth_cents"), integer(config, "bandwidth", 1, 10000))));
    // 正数金额乘折扣后加50再除100，实现按分四舍五入；Exact系列方法在long溢出时抛异常，而不是悄悄绕回负数。
    return Math.addExact(
            Math.multiplyExact(Math.multiplyExact(monthly, months), months == 12 ? 85 : 100), 50)
        / 100;
  }

  /** 运营维护SPU，写入新记录或更新原记录，并保存审计；active=false是下架，不删除已有交易。 */
  @Transactional
  public Object saveProduct(AuthService.Actor a, Map<String, Object> data) {
    a.staffOnly();
    String reason = input(data, "reason", 500);
    String id = Objects.toString(data.get("id"), id());
    var before = repository.findProduct(id);
    var p = new PlatformProduct();
    p.id = id;
    p.name = input(data, "name", 100);
    p.category = input(data, "category", 20);
    require(
        Set.of("ECS", "GPU", "RDS", "DISK").contains(p.category), 400, "INVALID_INPUT", "不支持的产品类型");
    p.description = input(data, "description", 5000);
    p.scenario = input(data, "scenario", 255);
    p.active = Boolean.TRUE.equals(data.get("active"));
    p.imageUrl = Objects.toString(data.get("image_url"), "");
    require(p.imageUrl.isEmpty() || p.imageUrl.startsWith("/"), 400, "INVALID_INPUT", "图片请使用站内路径");
    if (before == null) repository.insertProduct(p);
    else repository.updateProduct(p);
    events.audit(
        a.id(),
        "PRODUCT_SAVE",
        id,
        "原因：" + reason + "；前：" + json.json(before) + "；后：" + json.json(p));
    return p;
  }

  /** 锁定SKU，限制已售SKU关键规格不可变、库存不能低于预留加分配；保存新价格版本，不回写旧订单。 */
  @Transactional
  public Object saveSku(AuthService.Actor a, Map<String, Object> d) {
    a.staffOnly();
    String reason = input(d, "reason", 500),
        id = Objects.toString(d.get("id"), id()),
        product = input(d, "product_id", 64);
    require(repository.findProduct(product) != null, 404, "NOT_FOUND", "产品不存在");
    // 先锁住当前SKU再校验库存/版本；管理员不能把总量调到已预留和已分配之下。
    var old = repository.lockSku(id);
    int capacity = integer(d, "capacity", 0, 1000000);
    if (!old.isEmpty() && repository.countSkuOrders(id) > 0) {
      for (String field : List.of("product_id", "region", "zone", "cpu", "memory_gb", "adapter"))
        require(
            Objects.toString(old.get(0).get(field), "").equals(Objects.toString(d.get(field), "")),
            409,
            "SKU_IMMUTABLE",
            "已售SKU的地域、规格、产品及适配器不可变更，请新建SKU");
    }
    if (!old.isEmpty())
      require(
          capacity >= num(old.get(0), "reserved") + num(old.get(0), "allocated"),
          409,
          "INVENTORY_CONFLICT",
          "总库存不能小于预留与已分配之和");
    int version = old.isEmpty() ? 1 : (int) num(old.get(0), "version") + 1;
    String periods = input(d, "periods", 64);
    require(periods.matches("(1|3|12)(,(1|3|12))*"), 400, "INVALID_INPUT", "首版周期为1/3/12个月");
    String images = input(d, "images", 255);
    require(
        images.matches("(ubuntu-22|rocky-9)(,(ubuntu-22|rocky-9))*"),
        400,
        "INVALID_INPUT",
        "镜像配置无效");
    int min = integer(d, "min_disk", 40, 500), max = integer(d, "max_disk", min, 500);
    String adapter = Objects.toString(d.get("adapter"), "SIMULATED");
    require(
        Set.of("SIMULATED", "UNAVAILABLE").contains(adapter),
        400,
        "INVALID_INPUT",
        "暂只支持模拟供应商或不可售");
    repository.saveSku(
        id,
        product,
        input(d, "name", 100),
        input(d, "region", 40),
        input(d, "zone", 40),
        integer(d, "cpu", 1, 256),
        integer(d, "memory_gb", 1, 2048),
        integer(d, "base_cents", 1, 100000000),
        integer(d, "disk_cents", 0, 100000),
        integer(d, "bandwidth_cents", 0, 100000),
        version,
        Boolean.TRUE.equals(d.get("active")),
        capacity,
        adapter,
        images,
        min,
        max,
        integer(d, "max_bandwidth", 1, 1000),
        periods,
        integer(d, "grace_days", 1, 30),
        integer(d, "refund_days", 0, 30));
    var saved = repository.findSku(id);
    repository.insertPriceVersion(id(), id, version, json.json(saved), now());
    events.audit(
        a.id(), "SKU_SAVE", id, "原因：" + reason + "；前：" + json.json(old) + "；后：" + json.json(saved));
    return saved;
  }

  /** 提供公开目录选项。 */
  public Object regions() {
    return repository.listRegions();
  }

  /** 提供公开目录选项。 */
  public Object images() {
    return List.of(
        Map.of("id", "ubuntu-22", "name", "Ubuntu 22.04 LTS"),
        Map.of("id", "rocky-9", "name", "Rocky Linux 9"));
  }
}
