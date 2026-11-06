package com.cloudagent.business.dto;

import jakarta.validation.constraints.*;
import java.util.Map;

/** 应用平台输入；业务层继续验证当前租约、资源状态和模板版本。 */
public final class ApplicationRequests {
  public record Finance(
      @NotNull @Min(0) Long monthlyCostCents,
      @NotBlank String procurementExpiresAt,
      @NotBlank @Size(max = 500) String reason) {}

  private ApplicationRequests() {}

  public record Host(
      @NotBlank @Size(max = 128) String providerId,
      @NotBlank String skuId,
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(max = 255) String endpoint,
      @NotBlank String procurementExpiresAt,
      @Pattern(regexp = "LOCAL_LINUX|ALIYUN|SHARED_DEMO") String mode) {}

  public record Register(@NotBlank String token, @NotNull Map<String, Object> capabilities) {}

  public record Project(@NotBlank @Size(max = 100) String name, @NotBlank String allocationId) {}

  public record Plan(
      @NotBlank @Pattern(regexp = "DEPLOY|RESTART|ROLLBACK|LOGS|STOP|START") String kind,
      @Size(max = 64) String targetDeploymentId) {}

  public record Confirm(@NotBlank String planHash, boolean confirmed) {}
  public record Checkpoint(@Min(1) long attempt) {}
  public record Cancel(boolean confirmed) {}
  public record WelcomeMessage(@NotNull @Size(max = 500) String message) {}

  public record Event(long attempt, long sequence, @NotBlank @Size(max = 2000) String message) {}

  public record Completion(long attempt, boolean success, @NotNull Map<String, Object> result) {}

  public record Config(
      @NotNull @Size(max = 20) Map<String, String> variables,
      @NotNull @Size(max = 20) Map<String, String> secrets) {}
}
