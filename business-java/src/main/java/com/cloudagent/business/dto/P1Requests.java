package com.cloudagent.business.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;

/** P1 用例的明确请求契约；金额为整数分，比例为万分比，时间为带时区的 ISO-8601。 */
public final class P1Requests {
  private P1Requests() {}

  public record Coupon(
      @NotBlank @Size(max = 40) String code,
      @NotBlank @Size(max = 100) String name,
      @Min(0) long minimum_cents,
      @Min(1) long discount_cents,
      @Min(1) @Max(1000000) int capacity,
      @NotNull Instant starts_at,
      @NotNull Instant ends_at,
      boolean active,
      @NotBlank @Size(max = 500) String reason) {}

  public record Claim(@NotBlank @Size(max = 40) String code) {}

  public record Campaign(
      String id,
      @NotBlank @Size(max = 64) String product_id,
      @NotBlank @Size(max = 100) String title,
      @Min(1) @Max(5000) int rate_bps,
      boolean active,
      @NotBlank @Size(max = 500) String reason) {}

  public record Invoice(
      @NotBlank @Size(max = 64) String order_id,
      @NotBlank @Size(max = 100) String title,
      @NotNull @Pattern(regexp = "[A-Za-z0-9]{15,30}|PERSONAL") String tax_number) {}

  public record Review(
      boolean approve,
      @NotBlank @Size(max = 500) String reason,
      @Size(max = 100) String reference) {}

  public record Document(
      String id,
      @NotBlank @Size(max = 64) String product_id,
      @NotBlank @Size(max = 150) String title,
      @NotBlank @Size(max = 100000) String content,
      @NotNull Instant valid_until,
      @NotBlank @Size(max = 500) String reason) {}

  public record Decision(
      @NotBlank @Size(max = 30) String action, @NotBlank @Size(max = 500) String reason) {}

  public record Contact(
      @NotNull @Pattern(regexp = "EMAIL|SMS") String channel,
      @NotBlank @Size(max = 150) String destination) {}

  public record Verify(
      @NotBlank @Size(max = 64) String challenge_id,
      @NotNull @Pattern(regexp = "[0-9]{6}") String code) {}

  public record Reset(
      @NotBlank @Size(max = 64) String challenge_id,
      @NotNull @Pattern(regexp = "[0-9]{6}") String code,
      @NotBlank @Size(max = 72) String new_password) {}

  public record AutoRenew(
      boolean enabled, @Min(1) @Max(12) int months, @Min(1) long max_cents, boolean confirmed) {}

  public record Preview(
      @NotNull @Pattern(regexp = "PURCHASE|RENEWAL|REFUND") String action,
      @NotNull Map<String, Object> parameters) {}

  public record Confirm(boolean confirmed) {}

  public record Provider(
      @NotNull @Pattern(regexp = "ALIPAY_SANDBOX|ALIYUN_ECS|DELIVERY") String provider,
      @NotNull @Pattern(regexp = "DISABLED|SANDBOX|DRY_RUN|LOCAL") String mode,
      @NotNull Map<String, String> settings,
      @NotBlank @Size(max = 500) String reason) {}
}
