package com.cloudagent.business.integration;

import static com.cloudagent.business.common.ApiError.require;

import com.aliyuncs.*;
import com.aliyuncs.http.*;
import com.aliyuncs.profile.DefaultProfile;
import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.common.JsonCodec;
import java.util.*;
import org.springframework.stereotype.Component;

/** 阿里云固定 API 适配：ECS 只执行 RunInstances DryRun 预检，短信使用已配置的审核模板。没有任意域名/Action代理。 */
@Component
public class AliyunClient {
  public Map<String,Object> windowsAsset(String region,String instance){
    var result=call("ecs.aliyuncs.com","2014-05-26","DescribeInstances",region,Map.of("RegionId",region,"InstanceIds",json.json(List.of(instance))));
    require(result.get("Instances") instanceof Map,404,"CLOUD_HOST_NOT_FOUND","云实例不存在");
    var wrapper=(Map<?,?>)result.get("Instances");var rows=(List<?>)wrapper.get("Instance");
    require(rows!=null && rows.size()==1,404,"CLOUD_HOST_NOT_FOUND","云实例不存在");var host=(Map<?,?>)rows.get(0);
    require("windows".equalsIgnoreCase(Objects.toString(host.get("OSType"),"")) && "Running".equals(host.get("Status")),409,"WINDOWS_HOST_REQUIRED","需要运行中的Windows ECS");
    return Map.of("instance_id",instance,"os","WINDOWS","status","Running");
  }
  private final JsonCodec json;

  public AliyunClient(JsonCodec json) {
    this.json = json;
  }

  public boolean configured() {
    return !Objects.toString(System.getenv("ALIYUN_ACCESS_KEY_ID"), "").isBlank()
        && !Objects.toString(System.getenv("ALIYUN_ACCESS_KEY_SECRET"), "").isBlank();
  }

  private Map<String, Object> call(
      String domain, String version, String action, String region, Map<String, String> values) {
    require(configured(), 503, "PROVIDER_NOT_CONFIGURED", "阿里云AccessKey未配置");
    try {
      var profile =
          DefaultProfile.getProfile(
              region,
              System.getenv("ALIYUN_ACCESS_KEY_ID"),
              System.getenv("ALIYUN_ACCESS_KEY_SECRET"));
      var client = new DefaultAcsClient(profile);
      var request = new CommonRequest();
      request.setSysDomain(domain);
      request.setSysVersion(version);
      request.setSysAction(action);
      request.setSysMethod(MethodType.POST);
      request.setSysProtocol(ProtocolType.HTTPS);
      request.setSysConnectTimeout(5000);
      request.setSysReadTimeout(15000);
      values.forEach(request::putQueryParameter);
      try {
        return json.parse(client.getCommonResponse(request).getData());
      } finally {
        client.shutdown();
      }
    } catch (com.aliyuncs.exceptions.ClientException e) {
      if (action.equals("RunInstances") && "DryRunOperation".equals(e.getErrCode()))
        return Map.of("checked", true, "created", false, "mode", "DRY_RUN");
      throw requestFailure(e.getErrCode());
    }
  }

  /** 供应商原始报文可能携带身份/请求细节，只映射白名单错误，不向页面或日志透传原文。 */
  public static ApiError requestFailure(String code) {
    if ("InvalidAccountStatus.NotEnoughBalance".equals(code)
        || "Account.Arrearage".equals(code)
        || "InsufficientBalance".equals(code))
      return new ApiError(502, "ALIYUN_ACCOUNT_BALANCE", "阿里云账户余额不足或存在欠费，预检未通过；未创建资源");
    if ("Forbidden.RAM".equals(code) || "Forbidden.NoPermission".equals(code))
      return new ApiError(502, "ALIYUN_PERMISSION_DENIED", "RAM用户缺少接口或资源权限，预检未通过；未创建资源");
    if (code != null
        && (code.startsWith("InvalidParameter")
            || code.startsWith("InvalidZoneId")
            || code.startsWith("InvalidVSwitchId")
            || code.startsWith("InvalidSecurityGroupId")
            || code.startsWith("InvalidImageId")
            || code.startsWith("InvalidInstanceType")))
      return new ApiError(502, "ALIYUN_RESOURCE_CONFIG", "地域、可用区、规格、镜像或网络配置不匹配，请检查资源映射；未创建资源");
    return new ApiError(502, "ALIYUN_REQUEST_FAILED", "阿里云请求未通过，请检查权限、配额和配置；未创建资源");
  }

  public Object dryRun(Map<String, String> settings, String token) {
    var values = new LinkedHashMap<String, String>();
    for (String key :
        List.of("RegionId", "ZoneId", "InstanceType", "ImageId", "SecurityGroupId", "VSwitchId")) {
      String v = settings.get(key);
      require(v != null && !v.isBlank(), 400, "ALIYUN_CONFIG", "缺少阿里云配置：" + key);
      values.put(key, v);
    }
    values.put("Amount", "1");
    values.put("InstanceChargeType", "PostPaid");
    values.put("DryRun", "true");
    values.put("ClientToken", token);
    var result =
        call("ecs.aliyuncs.com", "2014-05-26", "RunInstances", settings.get("RegionId"), values);
    return Map.of("mode", "DRY_RUN", "created", false, "result", result);
  }

  public void sms(String phone, String text) {
    String sign = System.getenv("ALIYUN_SMS_SIGN_NAME");
    var matcher = java.util.regex.Pattern.compile("验证码：([0-9]{6})").matcher(text);
    boolean otp = matcher.find();
    String template =
        System.getenv(otp ? "ALIYUN_SMS_CODE_TEMPLATE_CODE" : "ALIYUN_SMS_NOTICE_TEMPLATE_CODE");
    require(
        sign != null && !sign.isBlank() && template != null && !template.isBlank(),
        503,
        "SMS_NOT_CONFIGURED",
        "阿里云短信签名或模板未配置");
    var result =
        call(
            "dysmsapi.aliyuncs.com",
            "2017-05-25",
            "SendSms",
            "cn-hangzhou",
            Map.of(
                "PhoneNumbers",
                phone,
                "SignName",
                sign,
                "TemplateCode",
                template,
                "TemplateParam",
                json.json(otp ? Map.of("code", matcher.group(1)) : Map.of("content", text))));
    require("OK".equals(result.get("Code")), 502, "SMS_REJECTED", "短信未确认成功");
  }

  /** 只读发现，不自动纳管、不自动出售、不改变资源状态；结果去除云端登录及网络敏感配置。 */
  public Object inventory(String region) {
    var response =
        call(
            "ecs.aliyuncs.com",
            "2014-05-26",
            "DescribeInstances",
            region,
            Map.of("RegionId", region, "PageSize", "50", "PageNumber", "1"));
    var items = new ArrayList<Map<String, Object>>();
    if (response.get("Instances") instanceof Map<?, ?> wrapper
        && wrapper.get("Instance") instanceof List<?> instances) {
      for (Object raw : instances)
        if (raw instanceof Map<?, ?> source) {
          Map<String, Object> item = new LinkedHashMap<>();
          for (String field :
              List.of(
                  "InstanceId",
                  "InstanceName",
                  "InstanceType",
                  "Status",
                  "Cpu",
                  "Memory",
                  "ExpiredTime",
                  "InstanceChargeType",
                  "ZoneId")) if (source.get(field) != null) item.put(field, source.get(field));
          items.add(item);
        }
    }
    return Map.of(
        "items",
        items,
        "total",
        response.getOrDefault("TotalCount", items.size()),
        "page_size",
        50,
        "read_only",
        true,
        "notice",
        "仅展示首50台；未纳管、未创建或修改任何资源");
  }
}
