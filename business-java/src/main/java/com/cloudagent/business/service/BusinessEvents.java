package com.cloudagent.business.service;

import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.repository.EventRepository;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** 应用层公共审计/通知用例；保留调用方事务，通知先持久化到 outbox 再异步投递。 */
@Service
public class BusinessEvents {
  private final EventRepository repository;
  private final JsonCodec json;

  public BusinessEvents(EventRepository repository, JsonCodec json) {
    this.repository = repository;
    this.json = json;
  }

  /** 审计内容限制长度，并携带请求号；调用方不得传入密码或令牌。 */
  public void audit(String actor, String action, String object, String detail) {
    repository.insertAudit(
        id(),
        actor,
        action,
        object,
        detail.substring(0, Math.min(2000, detail.length())),
        Objects.toString(MDC.get("requestId"), id()),
        now());
  }

  /** 唯一事件键用于去重；与业务写入共用当前事务。 */
  public void event(String key, String type, Map<String, Object> payload) {
    repository.insertEvent(id(), key, type, json.json(payload), now());
  }

  /** 按事件类型渲染通知模板，再把通知快照放进outbox；真正生成用户通知由JobRunner异步完成。 */
  public void notice(String key, String user, String title, String content) {
    String kind = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
    var templates = repository.findTemplates(kind);
    if (!templates.isEmpty()) {
      var template = templates.get(0);
      title = text(template, "title_template").replace("{title}", title);
      content = text(template, "content_template").replace("{content}", content);
    }
    title = title.substring(0, Math.min(100, title.length()));
    content = content.substring(0, Math.min(1000, content.length()));
    event(key, "NOTICE", Map.of("user_id", user, "title", title, "content", content));
  }
}
