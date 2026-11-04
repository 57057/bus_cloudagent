package com.cloudagent.business.controller;

import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.service.InternalToolService;
import com.cloudagent.business.web.ApiFilter;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * 两个面向服务端的入口：受控 AI 查询工具，以及模拟支付渠道回调。 工具调用同时验证服务令牌与用户上下文；模型传来的 user_id 不参与决定实际身份。
 * 支付回调验证的是渠道签名和渠道台账，不要求浏览器用户登录；这是不同的认证路径。 这里没有任意 SQL/HTTP/命令执行工具，模型只能选择 switch 中已获准的业务查询。
 */
@RestController
public class InternalController {
  private final InternalToolService service;

  public InternalController(InternalToolService service) {
    this.service = service;
  }

  /** 执行白名单工具。即使请求JSON传入别人的user_id，仍只使用签名上下文中的用户查询；最后写不含业务明细的审计。 */
  @PostMapping("/internal/tools/{tool}")
  public Object tool(
      @PathVariable String tool,
      @RequestHeader(value = "X-Service-Token", defaultValue = "") String token,
      @RequestHeader(value = "X-User-Context", defaultValue = "") String context,
      @RequestBody Map<String, Object> a) {
    return service.tool(tool, token, context, a);
  }

  /** 验证模拟渠道、整数分、回调HMAC和已成功渠道流水；校验通过才交给TradeService做付款状态转换。 */
  @PostMapping("/api/payments/callback/{channel}")
  public Object callback(
      @PathVariable String channel,
      @RequestHeader(value = "X-Payment-Signature", defaultValue = "") String signature,
      @RequestBody Map<String, Object> d) {
    return ApiFilter.result(service.callback(channel, signature, d));
  }
}
