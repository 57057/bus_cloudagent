package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudagent.business.common.*;
import com.cloudagent.business.dto.ApplicationRequests.*;
import com.cloudagent.business.repository.*;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ApplicationPlatformTest {
  private final JsonCodec json = new JsonCodec(new ObjectMapper());
  private final AuthService.Actor user = new AuthService.Actor("user", "USER", "session");
  private final ApplicationRepository r = mock(ApplicationRepository.class);
  private final PoolRepository pool = mock(PoolRepository.class);
  private final ApplicationService app =
      new ApplicationService(r, pool, json, mock(BusinessEvents.class), "unused", "unused");

  @Test
  void confirmationRequiresExplicitBrowserConfirmation() {
    var e =
        assertThrows(
            ApiError.class, () -> app.confirm(user, "p", new Confirm("hash", false), "idempotent"));
    assertEquals("CONFIRM_REQUIRED", e.code);
    verifyNoInteractions(r, pool);
  }

  @Test
  void rejectsForgedRunnerIdentityAndStaleAttempt() {
    when(pool.runner(anyString())).thenReturn(Map.of("id", "host-a", "generation", 3));
    when(r.task("t"))
        .thenReturn(Map.of("host_id", "host-b", "generation", 3, "attempt", 1, "state", "RUNNING"));
    assertEquals(
        "STALE_EXECUTION",
        assertThrows(ApiError.class, () -> app.event("credential", "t", new Event(1, 1, "stage")))
            .code);
    verify(r, never()).event(anyString(), anyLong(), anyLong(), anyString());
  }

  @Test
  void cannotAssertSuccessWithoutHealthEvidence() {
    when(pool.runner(anyString())).thenReturn(Map.of("id", "h", "generation", 3));
    when(r.task("t"))
        .thenReturn(
            Map.of(
                "host_id",
                "h",
                "generation",
                3,
                "attempt",
                1,
                "state",
                "RUNNING",
                "kind",
                "DEPLOY"));
    assertEquals(
        "HEALTH_REQUIRED",
        assertThrows(
                ApiError.class,
                () ->
                    app.complete(
                        "credential", "t", new Completion(1, true, Map.of("message", "done"))))
            .code);
    verify(r, never()).finish(anyString(), anyBoolean(), anyString());
  }

  @Test
  void poolRegistrationRejectsExpiredSingleUseToken() {
    when(pool.token(anyString()))
        .thenReturn(
            Map.of(
                "host_id",
                "h",
                "expires_at",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1))));
    when(pool.host("h")).thenReturn(Map.of("pool_state", "PREPARING"));
    var service = new PoolService(pool, json, mock(BusinessEvents.class));
    assertEquals(
        "REGISTRATION_EXPIRED",
        assertThrows(ApiError.class, () -> service.register(new Register("token", Map.of()))).code);
    verify(pool, never()).consume(anyString());
  }

  @Test
  void ordinaryUserCannotRegisterPlatformAssets() {
    var service = new PoolService(pool, json, mock(BusinessEvents.class));
    assertThrows(ApiError.class, () -> service.token(user, "h"));
    verifyNoInteractions(pool);
  }

  @Test
  void physicalHostCanBeRentedAgainWithoutReusingBusinessProviderIdentity() {
    var service = new PoolService(pool, json, mock(BusinessEvents.class));
    when(pool.allocation("first"))
        .thenReturn(
            List.of(Map.of("id", "lease-one", "provider_instance_id", "same-physical-server")));
    when(pool.allocation("second"))
        .thenReturn(
            List.of(Map.of("id", "lease-two", "provider_instance_id", "same-physical-server")));
    assertEquals("pool-lease-one", service.query("first").get("provider_id"));
    assertNotEquals(
        service.query("first").get("provider_id"), service.query("second").get("provider_id"));
  }
}
