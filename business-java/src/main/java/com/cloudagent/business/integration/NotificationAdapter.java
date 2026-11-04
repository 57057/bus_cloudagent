package com.cloudagent.business.integration;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.repository.DeliveryRepository;
import com.cloudagent.business.repository.ProviderRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 本地沙箱投递有独立台账；外部 SMTP 强制TLS，短信由阿里云已审核模板发送。凭据只从环境读取。 */
@Component
public class NotificationAdapter {
  private final DeliveryRepository r;
  private final AliyunClient aliyun;
  private final boolean demo;
  private final ProviderRepository providers;

  public NotificationAdapter(
      DeliveryRepository r,
      AliyunClient aliyun,
      ProviderRepository providers,
      @Value("${platform.demo}") boolean demo) {
    this.r = r;
    this.aliyun = aliyun;
    this.demo = demo;
    this.providers = providers;
  }

  public String mode() {
    var configuration = providers.config("DELIVERY");
    if (!configuration.isEmpty() && text(configuration.get(0), "mode").equals("DISABLED"))
      return "DISABLED";
    return Objects.toString(System.getenv("P1_DELIVERY_MODE"), demo ? "LOCAL" : "DISABLED");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void send(Map<String, Object> d) {
    String mode = mode();
    require(!mode.equals("DISABLED"), 503, "DELIVERY_DISABLED", "外部通知未配置");
    if (mode.equals("LOCAL")) {
      require(demo, 403, "LOCAL_ONLY", "本地收件箱仅限演示环境");
      r.receipt(
          text(d, "id"),
          text(d, "channel"),
          text(d, "destination"),
          text(d, "subject"),
          text(d, "content"),
          time(Instant.now().plusSeconds(600)));
      return;
    }
    if (text(d, "channel").equals("SMS")) {
      require(mode.equals("ALIYUN_SMS"), 503, "DELIVERY_UNAVAILABLE", "短信渠道未启用");
      aliyun.sms(text(d, "destination"), text(d, "content"));
      return;
    }
    require(mode.equals("SMTP"), 503, "DELIVERY_UNAVAILABLE", "邮件渠道未启用");
    String host = System.getenv("SMTP_HOST"),
        user = System.getenv("SMTP_USER"),
        password = System.getenv("SMTP_PASSWORD");
    require(
        host != null && user != null && password != null, 503, "SMTP_NOT_CONFIGURED", "SMTP配置不完整");
    var sender = new JavaMailSenderImpl();
    sender.setHost(host);
    sender.setPort(Integer.parseInt(Objects.toString(System.getenv("SMTP_PORT"), "587")));
    sender.setUsername(user);
    sender.setPassword(password);
    sender.setDefaultEncoding("UTF-8");
    var props = sender.getJavaMailProperties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.starttls.required", "true");
    props.put("mail.smtp.connectiontimeout", "5000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");
    var message = new SimpleMailMessage();
    message.setFrom(user);
    message.setTo(text(d, "destination"));
    message.setSubject(text(d, "subject"));
    message.setText(text(d, "content"));
    sender.send(message);
  }
}
