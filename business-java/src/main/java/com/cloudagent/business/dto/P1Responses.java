package com.cloudagent.business.dto;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 关键 P1 响应契约。验证码挑战不含验证码/摘要，操作预览不含任何确认凭据。 */
public final class P1Responses {
  private P1Responses() {}

  public record ContactChallenge(String challenge_id, String message, String mode) {}

  public record ActionPreview(
      String id,
      String action,
      String payload,
      String preview,
      String state,
      Timestamp expires_at,
      Timestamp created_at) {
    public static ActionPreview from(Map<String, Object> row) {
      return new ActionPreview(
          (String) row.get("id"),
          (String) row.get("action"),
          (String) row.get("payload"),
          (String) row.get("preview"),
          (String) row.get("state"),
          (Timestamp) row.get("expires_at"),
          (Timestamp) row.get("created_at"));
    }
  }

  public record KnowledgeSnapshot(long revision, List<Map<String, Object>> documents) {}

  public record ReportSummary(
      Map<String, Object> payments,
      Map<String, Object> refunds,
      long net_cents,
      List<Map<String, Object>> sales,
      String time_basis,
      String mode) {}
}
