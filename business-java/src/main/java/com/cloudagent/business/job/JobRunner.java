package com.cloudagent.business.job;

import com.cloudagent.business.service.JobService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时任务入口只负责触发；领取租约、重试和事务由 JobService 编排。 */
@Component
public class JobRunner {
  private final JobService service;

  public JobRunner(JobService service) {
    this.service = service;
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 2500)
  public void poll() {
    service.poll();
  }
}
