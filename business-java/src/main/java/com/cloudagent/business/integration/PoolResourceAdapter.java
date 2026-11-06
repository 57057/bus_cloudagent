package com.cloudagent.business.integration;

import com.cloudagent.business.service.PoolService;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 按稳定预留记录路由，POOL只分配已有平台资产；不会调用云厂商创建/删除实例。 */
@Component
@Primary
public class PoolResourceAdapter implements ResourceAdapter {
  private final PoolService pool;
  private final SimulatedResourceAdapter simulated;

  public PoolResourceAdapter(PoolService pool, SimulatedResourceAdapter simulated) {
    this.pool = pool;
    this.simulated = simulated;
  }

  public Map<String, Object> ensureCreated(String id, Instant expires) {
    return pool.supports(id) ? pool.provision(id, expires) : simulated.ensureCreated(id, expires);
  }

  public Map<String, Object> query(String id) {
    return pool.supports(id) ? pool.query(id) : simulated.query(id);
  }

  public void state(String id, String state) {
    if (pool.supports(id)) pool.state(id, state);
    else simulated.state(id, state);
  }

  public void renew(String id, Instant expires) {
    if (pool.supports(id)) pool.renew(id, expires);
    else simulated.renew(id, expires);
  }
}
