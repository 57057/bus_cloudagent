package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.cloudagent.business.common.*;
import com.cloudagent.business.repository.IdentityRepository;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class GatewayTest {
  @Test
  void serviceTokenCannotReplaceUserContext() {
    var identity =
        new InternalIdentity(
            new JsonCodec(new ObjectMapper()),
            mock(IdentityRepository.class),
            "test-service-token");
    assertThrows(ApiError.class, () -> identity.service("wrong"));
    assertThrows(ApiError.class, () -> identity.verify(""));
    assertThrows(ApiError.class, () -> identity.verify("forged.signature"));
  }

  @Test
  void monetaryCalculationsUseIntegerCents() {
    var sku = new HashMap<String, Object>();
    sku.put("base_cents", 3900L);
    sku.put("disk_cents", 5);
    sku.put("bandwidth_cents", 300);
    assertEquals(4400, CatalogService.unitPrice(sku, Map.of("disk_gb", 40, "bandwidth", 1), 1));
    assertEquals(44880, CatalogService.unitPrice(sku, Map.of("disk_gb", 40, "bandwidth", 1), 12));
  }

  @Test
  void passwordRulesAndRolesEnforced() {
    assertThrows(ApiError.class, () -> AuthService.validatePassword("short"));
    AuthService.validatePassword("longPassword123");
    assertThrows(ApiError.class, () -> new AuthService.Actor("u", "USER", "s").staffOnly());
    assertThrows(ApiError.class, () -> new AuthService.Actor("u", "OPERATOR", "s").adminOnly());
  }
}
