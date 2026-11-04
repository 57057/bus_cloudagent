package com.cloudagent.business.service;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.repository.ListRepository;
import java.util.*;
import org.springframework.stereotype.Service;

/** 列表用例：用户范围由认证身份决定；后台调用方必须先执行相应角色检查。 */
@Service
public class ListQueryService {
  private final ListRepository repository;

  public ListQueryService(ListRepository repository) {
    this.repository = repository;
  }

  public Object userPage(AuthService.Actor actor, String type, Map<String, String> query) {
    ApiError.require(
        Set.of("orders", "instances", "payments", "refunds", "tickets", "notifications")
            .contains(type),
        404,
        "NOT_FOUND",
        "不存在的资源类型");
    return page(type, actor.id(), query);
  }

  Object page(String type, String user, Map<String, String> query) {
    int page = CatalogService.parameter(query, "page", 1, 1, 100000);
    int size = CatalogService.parameter(query, "size", 20, 1, 100);
    return repository.page(type, user, query, page, size);
  }
}
