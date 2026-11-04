package com.cloudagent.business.integration;

import static com.cloudagent.business.common.ApiError.require;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.common.JsonCodec;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 官方 SDK 负责 RSA2 签名/响应验签，仅固定支付宝沙箱网关。真实生产网关不在本次可启用范围。 */
@Component
public class AlipaySandboxClient {
  public static final String GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
  private final JsonCodec json;

  public AlipaySandboxClient(JsonCodec json) {
    this.json = json;
  }

  private String env(String key) {
    return Objects.toString(System.getenv(key), "").strip();
  }

  private String key(String envName) {
    String file = env(envName);
    require(!file.isBlank(), 503, "PROVIDER_NOT_CONFIGURED", "支付宝沙箱密钥文件未配置");
    try {
      return Files.readString(Path.of(file)).replaceAll("-----[A-Z ]+-----|\\s", "");
    } catch (Exception e) {
      throw new ApiError(503, "PROVIDER_NOT_CONFIGURED", "支付宝沙箱密钥不可读");
    }
  }

  public boolean configured() {
    return !env("ALIPAY_SANDBOX_APP_ID").isBlank()
        && !env("ALIPAY_SANDBOX_SELLER_ID").isBlank()
        && !env("ALIPAY_PRIVATE_KEY_FILE").isBlank()
        && !env("ALIPAY_PUBLIC_KEY_FILE").isBlank();
  }

  private AlipayClient client() {
    require(configured(), 503, "PROVIDER_NOT_CONFIGURED", "请配置支付宝沙箱应用、商户及RSA2密钥");
    return new DefaultAlipayClient(
        GATEWAY,
        env("ALIPAY_SANDBOX_APP_ID"),
        key("ALIPAY_PRIVATE_KEY_FILE"),
        "json",
        "UTF-8",
        key("ALIPAY_PUBLIC_KEY_FILE"),
        "RSA2");
  }

  public static String yuan(long cents) {
    return BigDecimal.valueOf(cents, 2).toPlainString();
  }

  public String create(String id, long cents) {
    try {
      var q = new AlipayTradePrecreateRequest();
      q.setBizContent(
          json.json(
              Map.of(
                  "out_trade_no",
                  id,
                  "total_amount",
                  yuan(cents),
                  "subject",
                  "云栈沙箱租用订单",
                  "seller_id",
                  env("ALIPAY_SANDBOX_SELLER_ID"),
                  "timeout_express",
                  "15m")));
      var r = client().execute(q);
      require(r.isSuccess(), 502, "ALIPAY_REJECTED", "沙箱预下单未成功，请检查签约与应用配置");
      return r.getQrCode();
    } catch (AlipayApiException e) {
      throw new ApiError(502, "ALIPAY_UNAVAILABLE", "支付宝沙箱连接或响应验签失败");
    }
  }

  /** 查询结果必须同时匹配平台支付号、商户与整数金额；浏览器支付完成按钮不能代替此结果。 */
  public Map<String, Object> query(String id, long expected) {
    try {
      var q = new AlipayTradeQueryRequest();
      q.setBizContent(json.json(Map.of("out_trade_no", id)));
      var r = client().execute(q);
      if (!r.isSuccess()) {
        require("ACQ.TRADE_NOT_EXIST".equals(r.getSubCode()), 502, "ALIPAY_REJECTED", "支付宝沙箱查询失败");
        return Map.of(
            "state", "CREATED", "transaction_id", "pending-" + id, "amount_cents", expected);
      }
      @SuppressWarnings("unchecked")
      var body = (Map<String, Object>) json.parse(r.getBody()).get("alipay_trade_query_response");
      long cents =
          new BigDecimal(Objects.toString(body.get("total_amount")))
              .movePointRight(2)
              .longValueExact();
      require(
          id.equals(body.get("out_trade_no"))
              && (!body.containsKey("seller_id")
                  || env("ALIPAY_SANDBOX_SELLER_ID").equals(body.get("seller_id")))
              && cents == expected,
          400,
          "PAYMENT_AMOUNT_MISMATCH",
          "沙箱商户、支付号或金额不符");
      String status = Objects.toString(body.get("trade_status"));
      return Map.of(
          "state",
          Set.of("TRADE_SUCCESS", "TRADE_FINISHED").contains(status)
              ? "SUCCEEDED"
              : status.equals("TRADE_CLOSED") ? "CANCELLED" : "CREATED",
          "transaction_id",
          Objects.toString(body.get("trade_no"), "pending-" + id),
          "amount_cents",
          cents);
    } catch (AlipayApiException | ArithmeticException e) {
      throw new ApiError(502, "ALIPAY_UNAVAILABLE", "支付宝查询失败或金额格式错误");
    }
  }

  public String refund(String refund, String payment, long amount) {
    try {
      var q = new AlipayTradeRefundRequest();
      q.setBizContent(
          json.json(
              Map.of(
                  "out_trade_no",
                  payment,
                  "out_request_no",
                  refund,
                  "refund_amount",
                  yuan(amount))));
      var r = client().execute(q);
      require(r.isSuccess(), 502, "ALIPAY_REFUND_FAILED", "支付宝沙箱退款未确认成功，可用原请求号重试");
      return r.getTradeNo() + ":" + refund;
    } catch (AlipayApiException e) {
      throw new ApiError(502, "ALIPAY_UNAVAILABLE", "支付宝沙箱退款连接或验签失败");
    }
  }

  public void verifyCallback(Map<String, String> params) {
    try {
      require(
          AlipaySignature.rsaCheckV1(params, key("ALIPAY_PUBLIC_KEY_FILE"), "UTF-8", "RSA2")
              && env("ALIPAY_SANDBOX_APP_ID").equals(params.get("app_id"))
              && env("ALIPAY_SANDBOX_SELLER_ID").equals(params.get("seller_id")),
          401,
          "SIGNATURE_INVALID",
          "沙箱回调验签或商户校验失败");
    } catch (AlipayApiException e) {
      throw new ApiError(401, "SIGNATURE_INVALID", "沙箱回调验签失败");
    }
  }
}
