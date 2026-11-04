package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.dto.P1Requests.Preview;
import com.cloudagent.business.dto.P1Responses.ActionPreview;
import com.cloudagent.business.repository.AssistantActionRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 模型只能准备预览，确认接口不提供给 MCP。用户浏览器确认后仍调用原交易/退款服务重新校验，绝不自动付款。 */
@Service
public class AssistantActionService {
  private final AssistantActionRepository r;
  private final TradeService trade;
  private final ResourceService resources;
  private final JsonCodec json;
  private final BusinessEvents events;

  public AssistantActionService(
      AssistantActionRepository r,
      TradeService trade,
      ResourceService resources,
      JsonCodec json,
      BusinessEvents events) {
    this.r = r;
    this.trade = trade;
    this.resources = resources;
    this.json = json;
    this.events = events;
  }

  public Object list(AuthService.Actor a) {
    return r.list(a.id());
  }

  @Transactional
  public ActionPreview preview(AuthService.Actor a, Preview d) {
    require(
        d.action() != null
            && Set.of("PURCHASE", "RENEWAL", "REFUND").contains(d.action())
            && d.parameters() != null,
        400,
        "INVALID_ACTION",
        "缺少动作或参数");
    Object result;
    Map<String, Object> payload = new LinkedHashMap<>(d.parameters());
    if (d.action().equals("REFUND")) {
      String order = input(payload, "order_id", 64), item = input(payload, "item_id", 64);
      input(payload, "reason", 500);
      result = resources.previewRefund(a, order, item);
    } else {
      String instance = d.action().equals("RENEWAL") ? input(payload, "instance_id", 64) : null;
      result = trade.quote(a, payload, instance);
      @SuppressWarnings("unchecked")
      var quote = (Map<String, Object>) result;
      payload = Map.of("quote_id", quote.get("id"));
    }
    String id = id();
    r.insert(
        id,
        a.id(),
        d.action(),
        json.json(payload),
        json.json(result),
        time(Instant.now().plusSeconds(600)),
        now());
    events.audit(a.id(), "AI_ACTION_PREVIEW", id, d.action());
    return ActionPreview.from(r.lock(id, a.id()));
  }

  @Transactional
  public Object confirm(AuthService.Actor a, String id, boolean confirmed) {
    require(confirmed, 400, "CONFIRMATION_REQUIRED", "请先核对预览并明确确认");
    var p = r.lock(id, a.id());
    if (text(p, "state").equals("CONFIRMED")) return json.parse(p.get("result"));
    require(
        instant(p.get("expires_at")).isAfter(Instant.now()), 409, "PREVIEW_EXPIRED", "预览已过期，请重新准备");
    var payload = json.parse(p.get("payload"));
    Object result;
    if (text(p, "action").equals("REFUND")) {
      @SuppressWarnings("unchecked")
      var fresh =
          (Map<String, Object>)
              resources.previewRefund(
                  a, input(payload, "order_id", 64), input(payload, "item_id", 64));
      require(
          num(fresh, "amount_cents") == num(json.parse(p.get("preview")), "amount_cents"),
          409,
          "PREVIEW_CHANGED",
          "退款金额已变化，请重新预览");
      result = resources.requestRefund(a, payload);
    } else result = trade.createOrder(a, payload, "ai-" + id);
    r.done(id, json.json(result));
    events.audit(a.id(), "AI_ACTION_CONFIRMED", id, "用户明确确认；未自动支付");
    return result;
  }
}
