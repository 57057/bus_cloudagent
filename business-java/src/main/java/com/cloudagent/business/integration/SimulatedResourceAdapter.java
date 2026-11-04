package com.cloudagent.business.integration;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.repository.SimulatedResourceRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/**
 * 模拟云供应商，实际保存资源台账，不调用真实云厂商，也不创建可登录主机。 独立事务保留供应商侧已经确认的事实，便于测试本地提交失败之后的查询补偿。 唯一业务号让重复 ensureCreated
 * 找回原资源；状态更新再检查已释放资源不能恢复。
 */
@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SimulatedResourceAdapter implements ResourceAdapter {

  private final SimulatedResourceRepository repository;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public SimulatedResourceAdapter(SimulatedResourceRepository repository) {

    this.repository = repository;
  }

  /** 以业务号唯一约束创建或找回原模拟资源；调用超时后重试也不能换号重复开通。 */
  public Map<String, Object> ensureCreated(String key, Instant expires) {
    repository.insertProviderResource(key, "sim-ecs-" + key, time(expires), now());
    return query(key);
  }

  /** 按业务号查询供应商台账，供本地恢复或补偿使用。 */
  public Map<String, Object> query(String key) {
    return repository.findProviderResource(key);
  }

  /** 更新供应商侧状态；已释放资源只能保持RELEASED，不能被普通启动调用复活。 */
  public void state(String key, String state) {
    var row = query(key);
    require(
        !text(row, "state").equals("RELEASED") || state.equals("RELEASED"),
        409,
        "PROVIDER_RELEASED",
        "模拟资源已释放");
    repository.updateProviderState(state, now(), key);
  }

  /** 更新模拟供应商有效期；到期暂停的资源可恢复运行，主动STOPPED的资源不会因此自动改成RUNNING。 */
  public void renew(String key, Instant expires) {
    var row = query(key);
    require(!text(row, "state").equals("RELEASED"), 409, "PROVIDER_RELEASED", "模拟供应商无法恢复已释放资源");
    repository.renewProviderResource(time(expires), now(), key);
  }
}
