package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudagent.business.common.*;
import com.cloudagent.business.integration.PaymentAdapter;
import com.cloudagent.business.repository.*;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** 验证移入 Service 的权限、幂等边界和事务责任，避免仅检查类名与目录。 */
class LayeredServiceTest {
  private final JsonCodec json = new JsonCodec(new ObjectMapper());
  private final AuthService.Actor user = new AuthService.Actor("u1", "USER", "s1");

  @Test
  void adminAuthorizationRunsBeforeDataAccess() {
    var repository = mock(AdminRepository.class);
    var service =
        new AdminService(
            json,
            mock(BusinessEvents.class),
            repository,
            mock(ListQueryService.class),
            mock(CatalogService.class),
            mock(ResourceService.class),
            mock(SupportService.class),
            mock(JobService.class));
    assertThrows(ApiError.class, () -> service.summary(user));
    assertThrows(ApiError.class, () -> service.user("u2", Map.of("reason", "测试"), user));
    verifyNoInteractions(repository);
  }

  @Test
  void selfLockoutIsRejectedBeforeWrites() {
    var repository = mock(AdminRepository.class);
    when(repository.lockUser("admin")).thenReturn(Map.of("role", "ADMIN", "enabled", true));
    var service =
        new AdminService(
            json,
            mock(BusinessEvents.class),
            repository,
            mock(ListQueryService.class),
            mock(CatalogService.class),
            mock(ResourceService.class),
            mock(SupportService.class),
            mock(JobService.class));
    var admin = new AuthService.Actor("admin", "ADMIN", "s");
    assertThrows(
        ApiError.class, () -> service.user("admin", Map.of("role", "USER", "reason", "测试"), admin));
    verify(repository).lockUser("admin");
    verifyNoMoreInteractions(repository);
  }

  @Test
  void notificationWriteAlwaysIncludesAuthenticatedOwner() {
    var repository = mock(SupportRepository.class);
    var service =
        new SupportService(mock(BusinessEvents.class), repository, mock(TradeService.class));
    service.markNotificationRead(user, "notice-1");
    verify(repository).markNotificationRead("notice-1", "u1");
  }

  @Test
  void userListCannotSelectAdminResources() {
    var repository = mock(ListRepository.class);
    var service = new ListQueryService(repository);
    assertThrows(ApiError.class, () -> service.userPage(user, "users", Map.of()));
    service.userPage(user, "orders", Map.of("page", "2"));
    verify(repository).page("orders", "u1", Map.of("page", "2"), 2, 20);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void simulationChecksOwnershipBeforeCallingProvider() {
    var repository = mock(TradeRepository.class);
    var payment = mock(PaymentAdapter.class);
    when(repository.findOwned("payments", false, "p1", "u1"))
        .thenThrow(new ApiError(404, "NOT_FOUND", "不存在"));
    var service =
        new TradeService(
            json,
            mock(BusinessEvents.class),
            repository,
            mock(CatalogService.class),
            payment,
            mock(MarketingService.class),
            10,
            15);
    assertThrows(ApiError.class, () -> service.simulate("p1", Map.of("confirmed", true), user));
    verifyNoInteractions(payment);
  }

  @Test
  void movedAdminWritesKeepPublicTransactionalEntryPoints() throws Exception {
    assertNotNull(
        AdminService.class
            .getMethod("template", Map.class, AuthService.Actor.class)
            .getAnnotation(Transactional.class));
    assertNotNull(
        AdminService.class
            .getMethod("user", String.class, Map.class, AuthService.Actor.class)
            .getAnnotation(Transactional.class));
    assertNotNull(
        TradeService.class
            .getMethod("createOrder", AuthService.Actor.class, Map.class, String.class)
            .getAnnotation(Transactional.class));
  }
}
