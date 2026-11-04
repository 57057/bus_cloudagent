package com.cloudagent.business.service;

import com.cloudagent.business.entity.*;
import com.cloudagent.business.repository.LegacyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 旧版 Java/Python 分层时的查询兼容代码，保留原目录、推广示例和历史表查询。 当前 P0 的 /internal/tools 由 InternalController
 * 处理，不再把这里的示例佣金当成运营事实。 本类仍由 Spring 注册成 Service，但“被注册”不等于当前控制器一定会调用它。 阅读新平台应先看
 * controller/service/repository 包；本类适合对照了解旧版 Mapper 查询方式。
 */
@Service
public class BusinessService {
  private final LegacyRepository repository;
  private final Map<String, Map<String, Object>> catalog, promotions;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public BusinessService(LegacyRepository repository, ObjectMapper json) throws Exception {
    this.repository = repository;
    try (var in = new ClassPathResource("catalog.json").getInputStream()) {
      catalog = json.readValue(in, new TypeReference<>() {});
    }
    try (var in = new ClassPathResource("promotions.json").getInputStream()) {
      promotions = json.readValue(in, new TypeReference<>() {});
    }
  }

  /** 旧查询接口的status/data成功包装，与P0普通HTTP的code/message/data/requestId不是同一响应格式。 */
  public static Map<String, Object> ok(Object data) {
    return Map.of("status", "success", "data", data);
  }

  /** 旧查询的非空字符串检查；没有做真正的登录认证，不能把参数存在等同于身份可信。 */
  public static String required(Object value) {
    if (!(value instanceof String s) || s.isBlank())
      throw new IllegalArgumentException("缺少必要字符串参数");
    return s;
  }

  /** 将旧查询的数量限制为1~50，之后拼接LIMIT使用的是已校验整数，不是直接拼接用户原始文本。 */
  private int limit(Map<String, Object> a) {
    Object n = a.getOrDefault("limit", 5);
    int v = new BigDecimal(n.toString()).intValueExact();
    if (v < 1 || v > 50) throw new IllegalArgumentException("limit应为1到50");
    return v;
  }

  /** 旧工具名称分派器：通过Mapper查询历史订单/实例，或查询旧版目录与推广示例。当前P0工具入口不调用这个分派器。 */
  public Map<String, Object> execute(String tool, Map<String, Object> a) {
    return switch (tool) {
      case "query_user_orders" -> ok(repository.listOrders(required(a.get("user_id")), limit(a)));
      case "query_user_instances" ->
          ok(repository.listInstances(required(a.get("user_id")), limit(a)));
      case "analyze_instance_usage" ->
          usage(required(a.get("instance_id")), required(a.get("user_id")));
      case "search_product_catalog" -> products(required(a.get("keyword")), false);
      case "get_promotable_products" -> products("", true);
      case "get_promotion_materials" ->
          promotion(required(a.get("product_id")), Objects.toString(a.get("user_id"), ""));
      default -> throw new IllegalArgumentException("未知业务工具");
    };
  }

  /** 从classpath中的旧catalog.json筛选示例产品；不是P0后台维护的实时数据库目录。 */
  private Map<String, Object> products(String keyword, boolean promotable) {
    String q = keyword.toLowerCase(Locale.ROOT);
    List<Map<String, Object>> result = new ArrayList<>();
    catalog.forEach(
        (id, p) -> {
          if (promotable && id.equals("P_ESSD_PL1")) return;
          boolean match =
              p.get("name").toString().toLowerCase(Locale.ROOT).contains(q)
                  || ((List<?>) p.get("keywords"))
                      .stream().anyMatch(k -> k.toString().toLowerCase(Locale.ROOT).contains(q));
          if (promotable || match)
            result.add(
                Map.of("product_id", id, "product_name", p.get("name"), "price", p.get("price")));
        });
    if (result.isEmpty())
      return Map.of(
          "status",
          "not_found",
          "message",
          "未找到匹配产品",
          "recommendation",
          Map.of("product_id", "P_ALL_000", "product_name", "全场通用云产品活动"));
    return ok(result);
  }

  /** 读取旧示例推广资料并拼接经过URL编码的邀请参数；不能当成P0已经上线的佣金业务。 */
  private Map<String, Object> promotion(String id, String user) {
    var p = promotions.getOrDefault(id, promotions.get("P_ALL_000"));
    String link = p.get("base_link").toString();
    if (!user.isBlank())
      link +=
          "?inviter="
              + URLEncoder.encode(user, StandardCharsets.UTF_8)
              + "&pid="
              + URLEncoder.encode(id, StandardCharsets.UTF_8);
    return ok(
        Map.of(
            "product_id",
            id,
            "activity_title",
            p.get("title"),
            "selling_points",
            p.get("desc"),
            "exclusive_link",
            link,
            "commission_rate",
            p.get("commission_rate")));
  }

  /** 先检查历史实例归属，再聚合监控并按固定阈值分类；这里是旧版规则诊断，不是大模型推理。 */
  private Map<String, Object> usage(String id, String user) {
    if (repository.countOwnedInstance(id, user) == 0)
      return Map.of("status", "error", "message", "未找到该实例，或您无权查看该实例监控数据。");
    var row = repository.aggregateMetrics(id, user);
    if (row == null || ((Number) row.get("days_count")).longValue() == 0)
      return Map.of("status", "error", "message", "未查询到该实例近7天监控数据");
    double cpu = number(row.get("cpu_usage_percent")),
        mem = number(row.get("memory_usage_percent"));
    String diagnosis =
        cpu < 10 && mem < 30
            ? "RESOURCES_IDLE"
            : cpu > 70 || mem > 80 ? "RESOURCES_TIGHT" : "RESOURCES_NORMAL";
    return ok(
        Map.of(
            "instance_id",
            id,
            "owner_id",
            user,
            "metrics_7d_avg",
            Map.of(
                "cpu_usage_percent",
                cpu,
                "memory_usage_percent",
                mem,
                "network_out_bandwidth_mbps",
                number(row.get("network_out_bandwidth_mbps"))),
            "diagnosis",
            diagnosis));
  }

  /** 将可能为null的旧聚合结果转double用于展示/监控判断，不用于P0整数分结算。 */
  private double number(Object n) {
    return n == null ? 0 : ((Number) n).doubleValue();
  }
}
