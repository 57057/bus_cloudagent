package com.cloudagent.business.job;

import com.cloudagent.business.service.DeliveryService;
import com.cloudagent.business.service.ProviderService;
import com.cloudagent.business.service.RenewalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时入口不执行数据库逻辑；各子流程独立失败，投递故障不会阻断自动续费。 */
@Component
public class P1JobRunner {
  private final DeliveryService delivery;
  private final RenewalService renewals;
  private final ProviderService providers;
  private final boolean enabled;

  public P1JobRunner(
      DeliveryService delivery,
      RenewalService renewals,
      ProviderService providers,
      @Value("${platform.jobs-enabled}") boolean enabled) {
    this.delivery = delivery;
    this.renewals = renewals;
    this.providers = providers;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 6000)
  public void deliveries() {
    if (enabled) delivery.poll();
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 8000)
  public void renewals() {
    if (enabled) renewals.poll();
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 5000)
  public void payments() {
    if (enabled) providers.reconcile();
  }
}
