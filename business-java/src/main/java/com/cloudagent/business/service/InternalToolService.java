package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.repository.InternalToolRepository;
import java.util.*;

/**
 * 两个面向服务端的入口：受控 AI 查询工具，以及模拟支付渠道回调。 工具调用同时验证服务令牌与用户上下文；模型传来的 user_id 不参与决定实际身份。
 * 支付回调验证的是渠道签名和渠道台账，不要求浏览器用户登录；这是不同的认证路径。 这里没有任意 SQL/HTTP/命令执行工具，模型只能选择 switch 中已获准的业务查询。
 */
@org.springframework.stereotype.Service
public class InternalToolService {
  private final BusinessEvents events;

  private final InternalToolRepository repository;
  private final InternalIdentity identity;
  private final TradeService trade;
  private final CatalogService catalog;
  private final AssistantActionService actions;
  private final MarketingService marketing;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public InternalToolService(
      BusinessEvents events,
      InternalToolRepository repository,
      InternalIdentity identity,
      TradeService trade,
      CatalogService catalog,
      AssistantActionService actions,
      MarketingService marketing) {
    this.events = events;

    this.repository = repository;
    this.identity = identity;
    this.trade = trade;
    this.catalog = catalog;
    this.actions = actions;
    this.marketing = marketing;
  }

  /** 执行白名单工具。即使请求JSON传入别人的user_id，仍只使用签名上下文中的用户查询；最后写不含业务明细的审计。 */
  public Object tool(String tool, String token, String context, Map<String, Object> a) {
    identity.service(token);
    var p = identity.verify(context);
    String user = text(p, "user_id");
    int limit = a.containsKey("limit") ? integer(a, "limit", 1, 50) : 5;
    Object data =
        switch (tool) {
          case "query_user_orders" -> repository.listUserOrders(user, limit);
          case "query_user_instances" -> repository.listUserInstances(user, limit);
          case "analyze_instance_usage" -> {
            trade.owned("instances", input(a, "instance_id", 64), user, false);
            yield Map.of("samples", List.of(), "message", "暂无监控数据，禁止推测使用率", "source", "SIMULATED");
          }
          case "search_product_catalog" -> {
            String keyword = input(a, "keyword", 100);
            yield Map.of(
                "products",
                catalog.products(Map.of("keyword", keyword, "size", "20")),
                "currency",
                "CNY",
                "money_unit",
                "cents",
                "mode",
                "SIMULATED",
                "skus",
                repository.searchAvailableSkus("%" + keyword + "%", "%" + keyword + "%"));
          }
          case "get_promotable_products" ->
              marketing.campaigns(
                  new AuthService.Actor(user, "USER", text(p, "auth_session")), false);
          case "get_promotion_materials" ->
              marketing.materials(
                  new AuthService.Actor(user, "USER", text(p, "auth_session")),
                  input(a, "product_id", 64));
          case "prepare_business_action" -> {
            require(a.get("parameters") instanceof Map, 400, "INVALID_ACTION", "动作参数必须是对象");
            @SuppressWarnings("unchecked")
            var parameters = (Map<String, Object>) a.get("parameters");
            yield actions.preview(
                new AuthService.Actor(user, "USER", text(p, "auth_session")),
                new com.cloudagent.business.dto.P1Requests.Preview(
                    input(a, "action", 20), parameters));
          }
          default -> throw new ApiError(400, "TOOL_NOT_ALLOWED", "工具未获授权");
        };
    events.audit(user, "AI_TOOL_QUERY", tool, "仅当前登录用户范围；不记录查询内容与业务明细");
    return Map.of("status", "success", "data", data);
  }

  /** 验证模拟渠道、整数分、回调HMAC和已成功渠道流水；校验通过才交给TradeService做付款状态转换。 */
  public Object callback(String channel, String signature, Map<String, Object> d) {
    require(channel.equals("SIMULATED"), 400, "CHANNEL_UNAVAILABLE", "未配置真实渠道");
    String payment = input(d, "payment_id", 64),
        provider = input(d, "provider_id", 64),
        currency = input(d, "currency", 3);
    long amount;
    try {
      amount =
          new java.math.BigDecimal(Objects.toString(d.get("amount_cents"), "")).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      throw new ApiError(400, "INVALID_INPUT", "支付金额须为整数分");
    }
    require(amount > 0, 400, "INVALID_INPUT", "支付金额须大于0");
    if (identity.token.isBlank()
        || !AuthService.equal(
            AuthService.hmac(
                identity.token,
                payment + "|" + provider + "|" + amount + "|" + currency + "|SIMULATED"),
            signature)) {
      events.audit("PAYMENT_CALLBACK", "CALLBACK_REJECTED", payment, "支付回调验签失败；不记录签名");
      throw new ApiError(401, "SIGNATURE_INVALID", "支付回调验签失败");
    }
    // The simulated channel ledger is authoritative, including merchant ownership and amount.
    var rows = repository.findSuccessfulProviderPayment(payment, provider);
    if (rows.isEmpty() || num(rows.get(0), "amount_cents") != amount || !currency.equals("CNY")) {
      events.audit("PAYMENT_CALLBACK", "CALLBACK_REJECTED", payment, "支付单未知、未确认成功或币种/金额不符");
      throw new ApiError(400, "PAYMENT_AMOUNT_MISMATCH", "渠道流水、币种或金额不符");
    }
    return trade.paymentNotification(payment, provider, amount, currency);
  }
}
