package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.dto.P1Requests.Document;
import com.cloudagent.business.dto.P1Responses.KnowledgeSnapshot;
import com.cloudagent.business.repository.KnowledgeRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档草稿不可原地覆盖发布版本；提交、独立审核、发布与下架均审计，失效内容不参与新回答。 */
@Service
public class KnowledgeService {
  private final KnowledgeRepository r;
  private final BusinessEvents events;

  public KnowledgeService(KnowledgeRepository r, BusinessEvents events) {
    this.r = r;
    this.events = events;
  }

  public Object list(AuthService.Actor a) {
    a.staffOnly();
    return r.list();
  }

  public Object detail(AuthService.Actor a, String id) {
    a.staffOnly();
    return r.find(id);
  }

  @Transactional
  public Object draft(AuthService.Actor a, Document d) {
    a.staffOnly();
    require(
        r.productExists(d.product_id()) == 1 && d.valid_until().isAfter(Instant.now()),
        400,
        "INVALID_DOCUMENT",
        "产品不存在或文档已过期");
    int version = 1;
    if (d.id() != null && !d.id().isBlank()) {
      var old = r.lock(d.id());
      require(text(old, "product_id").equals(d.product_id()), 409, "DOCUMENT_PRODUCT", "版本不可改产品");
      version = (int) num(old, "version") + 1;
    }
    String id = id();
    r.insert(
        id, d.product_id(), d.title(), d.content(), version, a.id(), time(d.valid_until()), now());
    events.audit(a.id(), "KNOWLEDGE_DRAFT", id, d.reason());
    return r.lock(id);
  }

  @Transactional
  public Object transition(AuthService.Actor a, String id, String action, String reason) {
    a.staffOnly();
    var initial = r.find(id); // 同产品发布串行化，防止两个同名版本同时成为当前版本。
    r.lockProduct(text(initial, "product_id"));
    var d = r.lock(id);
    String state = text(d, "state");
    String target;
    switch (action) {
      case "SUBMIT" -> {
        require(Set.of("DRAFT", "REJECTED").contains(state), 409, "DOCUMENT_STATE", "只可提交草稿或退回版本");
        target = "SUBMITTED";
      }
      case "PUBLISH" -> {
        require(
            state.equals("SUBMITTED") && !text(d, "author_id").equals(a.id()),
            409,
            "REVIEW_REQUIRED",
            "需另一位运营人员审核提交版本");
        require(
            instant(d.get("valid_until")).isAfter(Instant.now()), 409, "DOCUMENT_EXPIRED", "文档已过期");
        r.retireTitle(text(d, "product_id"), text(d, "title"), id);
        target = "PUBLISHED";
      }
      case "REJECT" -> {
        require(
            state.equals("SUBMITTED") && !text(d, "author_id").equals(a.id()),
            409,
            "REVIEW_REQUIRED",
            "需独立审核提交版本");
        target = "REJECTED";
      }
      case "RETIRE" -> {
        require(state.equals("PUBLISHED"), 409, "DOCUMENT_STATE", "只能下架发布版本");
        target = "RETIRED";
      }
      default -> throw new com.cloudagent.business.common.ApiError(400, "INVALID_ACTION", "无效文档动作");
    }
    r.state(id, target, a.id(), reason);
    events.audit(a.id(), "KNOWLEDGE_" + action, id, reason);
    return r.lock(id);
  }

  public KnowledgeSnapshot snapshot() {
    var docs = r.published(now());
    return new KnowledgeSnapshot(r.revision(), docs);
  }
}
