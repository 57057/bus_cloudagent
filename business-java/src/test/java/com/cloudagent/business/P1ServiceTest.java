package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudagent.business.common.*;
import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.integration.AlipaySandboxClient;
import com.cloudagent.business.integration.NotificationAdapter;
import com.cloudagent.business.repository.*;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 新业务的权限、精度及单次执行边界；外部 SDK 不触网，真实状态机另由 HTTP/MySQL 验收。 */
class P1ServiceTest {
  @Test
  void recoveryLimitsAreBoundToServerObservedSource() {
    var r = mock(DeliveryRepository.class);
    when(r.lockRequestLimit(anyString(), any(), any())).thenReturn(Map.of("failures", 20));
    var service =
        new DeliveryService(
            r,
            mock(BusinessEvents.class),
            mock(NotificationAdapter.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class),
            "test-pepper");
    var error =
        assertThrows(
            ApiError.class,
            () -> service.requestReset(new Contact("EMAIL", "test@example.com"), "127.0.0.1"));
    assertEquals(429, error.status);
    verify(r, never()).countRequest(anyString());
  }

  private final AuthService.Actor user = new AuthService.Actor("u1", "USER", "s1");
  private final JsonCodec json = new JsonCodec(new ObjectMapper());

  private Map<String, Object> grant() {
    return Map.of(
        "active",
        true,
        "starts_at",
        Timestamp.from(Instant.now().minusSeconds(1)),
        "ends_at",
        Timestamp.from(Instant.now().plusSeconds(600)),
        "state",
        "AVAILABLE",
        "minimum_cents",
        1000,
        "discount_cents",
        2000);
  }

  @Test
  void couponNeverCreatesNegativeOrZeroOrder() {
    var r = mock(MarketingRepository.class);
    when(r.lockGrant("g", "u1")).thenReturn(grant());
    var s = new MarketingService(r, mock(BusinessEvents.class));
    assertEquals(1040, s.discount(user, "g", 1041));
    assertThrows(ApiError.class, () -> s.discount(user, "g", 999));
  }

  @Test
  void couponRequiresAuthenticatedOwner() {
    var r = mock(MarketingRepository.class);
    when(r.lockGrant("foreign", "u1")).thenThrow(new ApiError(404, "NOT_FOUND", "不存在"));
    assertThrows(
        ApiError.class,
        () -> new MarketingService(r, mock(BusinessEvents.class)).discount(user, "foreign", 10000));
  }

  @Test
  void commissionUsesIntegerNetAfterRefund() {
    var r = mock(MarketingRepository.class);
    when(r.benefit("o")).thenReturn(List.of(Map.of("promoter_id", "p", "rate_bps", 500)));
    when(r.netCommission("o")).thenReturn(151L);
    var s = new MarketingService(r, mock(BusinessEvents.class));
    s.refunded(Map.of("id", "o", "total_cents", 3022, "refunded_cents", 1006), "f1");
    verify(r).ledger(anyString(), eq("p"), eq("o"), eq("refund:f1"), eq(-51L), eq("REFUND"), any());
    verify(r).flagInvoice("o");
  }

  @Test
  void publicationCannotBeSelfReviewed() {
    var r = mock(KnowledgeRepository.class);
    var d =
        Map.<String, Object>of(
            "id", "d", "product_id", "ecs", "author_id", "u1", "state", "SUBMITTED");
    when(r.find("d")).thenReturn(d);
    when(r.lock("d")).thenReturn(d);
    var s = new KnowledgeService(r, mock(BusinessEvents.class));
    assertThrows(
        ApiError.class,
        () -> s.transition(new AuthService.Actor("u1", "OPERATOR", "s"), "d", "PUBLISH", "review"));
    verify(r, never()).state(any(), any(), any(), any());
  }

  @Test
  void modelCannotConfirmWithoutUserFlag() {
    var r = mock(AssistantActionRepository.class);
    var t = mock(TradeService.class);
    var s =
        new AssistantActionService(
            r, t, mock(ResourceService.class), json, mock(BusinessEvents.class));
    assertThrows(ApiError.class, () -> s.confirm(user, "p", false));
    verifyNoInteractions(r, t);
  }

  @Test
  void confirmedPreviewReturnsOriginalResultWithoutNewOrder() {
    var r = mock(AssistantActionRepository.class);
    var t = mock(TradeService.class);
    when(r.lock("p", "u1"))
        .thenReturn(Map.of("state", "CONFIRMED", "result", "{\"id\":\"original\"}"));
    var s =
        new AssistantActionService(
            r, t, mock(ResourceService.class), json, mock(BusinessEvents.class));
    assertEquals(Map.of("id", "original"), s.confirm(user, "p", true));
    verifyNoInteractions(t);
  }

  @Test
  void expiredPreviewCannotCreateOrder() {
    var r = mock(AssistantActionRepository.class);
    var t = mock(TradeService.class);
    when(r.lock("p", "u1"))
        .thenReturn(
            Map.of(
                "state", "PENDING", "expires_at", Timestamp.from(Instant.now().minusSeconds(1))));
    var s =
        new AssistantActionService(
            r, t, mock(ResourceService.class), json, mock(BusinessEvents.class));
    assertThrows(ApiError.class, () -> s.confirm(user, "p", true));
    verifyNoInteractions(t);
  }

  @Test
  void alipayAmountsHaveExactCentScale() {
    assertEquals("0.01", AlipaySandboxClient.yuan(1));
    assertEquals("30.22", AlipaySandboxClient.yuan(3022));
  }
}
