package com.cloudagent.business.controller;

import static com.cloudagent.business.web.ApiFilter.actor;

import com.cloudagent.business.service.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 业务事件SSE来自持久化记录，不使用模型文本模拟部署进度。 */
@RestController
public class ApplicationEventsController {
  private final ApplicationService apps;
  private final AuthService auth;
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          0,
          16,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new ThreadPoolExecutor.AbortPolicy());

  public ApplicationEventsController(ApplicationService apps, AuthService auth) {
    this.apps = apps;
    this.auth = auth;
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }

  @GetMapping(
      value = "/api/deployments/{id}/events/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) Long last,
      HttpServletRequest request) {
    apps.deployment(actor(request), id);
    String token = request.getHeader("Authorization").substring(7);
    long initial = Math.max(after, last == null ? 0 : last);
    var emitter = new SseEmitter(16000L);
    try {
      executor.execute(
          () -> {
            long cursor = initial;
            try {
              for (int i = 0; i < 12; i++) {
                var current = auth.authenticate(token); // 退出或停用后不能继续读取事件。
                @SuppressWarnings("unchecked")
                var rows = (List<Map<String, Object>>) apps.events(current, id, cursor);
                for (var row : rows) {
                  cursor = ((Number) row.get("id")).longValue();
                  emitter.send(
                      SseEmitter.event().id(Long.toString(cursor)).name("progress").data(row));
                }
                emitter.send(SseEmitter.event().comment("heartbeat"));
                Thread.sleep(1000);
              }
              emitter.complete();
            } catch (Exception e) {
              emitter.completeWithError(e);
            }
          });
    } catch (RejectedExecutionException e) {
      emitter.completeWithError(e);
    }
    return emitter;
  }
}
