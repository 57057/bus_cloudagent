package com.cloudagent.business.repository;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 文档版本与发布指针。检索始终从已发布且未过期的行中取，变更递增全局知识修订号。 */
@Repository
public class KnowledgeRepository {
  private final SqlExecutor s;

  public KnowledgeRepository(SqlExecutor s) {
    this.s = s;
  }

  public List<Map<String, Object>> list() {
    return s.list(
        "SELECT"
            + " id,product_id,title,version,state,author_id,reviewer_id,valid_until,review_reason,created_at"
            + " FROM p1_documents ORDER BY created_at DESC LIMIT 200");
  }

  public Map<String, Object> find(String id) {
    return s.one("SELECT * FROM p1_documents WHERE id=?", id);
  }

  public Map<String, Object> lock(String id) {
    return s.one("SELECT * FROM p1_documents WHERE id=? FOR UPDATE", id);
  }

  public void insert(
      String id,
      String product,
      String title,
      String content,
      int version,
      String user,
      Timestamp valid,
      Timestamp now) {
    s.run(
        "INSERT INTO"
            + " p1_documents(id,product_id,title,content,version,state,author_id,valid_until,created_at)"
            + " VALUES(?,?,?,?,?,'DRAFT',?,?,?)",
        id,
        product,
        title,
        content,
        version,
        user,
        valid,
        now);
  }

  public void state(String id, String state, String reviewer, String reason) {
    s.run(
        "UPDATE p1_documents SET state=?,reviewer_id=?,review_reason=? WHERE id=?",
        state,
        reviewer,
        reason,
        id);
    s.run("UPDATE p1_knowledge_revision SET revision=revision+1 WHERE id=1");
  }

  public List<Map<String, Object>> published(Timestamp now) {
    return s.list(
        "SELECT d.id,d.product_id,d.title,d.content,d.version,d.valid_until FROM p1_documents d"
            + " JOIN p0_products p ON p.id=d.product_id WHERE d.state='PUBLISHED' AND"
            + " d.valid_until>? AND p.active=TRUE ORDER BY d.id LIMIT 1000",
        now);
  }

  public long revision() {
    return s.count("SELECT revision FROM p1_knowledge_revision WHERE id=1");
  }

  public long productExists(String product) {
    return s.count("SELECT COUNT(*) FROM p0_products WHERE id=?", product);
  }

  public void retireTitle(String product, String title, String except) {
    s.run(
        "UPDATE p1_documents SET state='RETIRED' WHERE product_id=? AND title=? AND"
            + " state='PUBLISHED' AND id<>?",
        product,
        title,
        except);
  }

  public void lockProduct(String product) {
    s.one("SELECT id FROM p0_products WHERE id=? FOR UPDATE", product);
  }
}
