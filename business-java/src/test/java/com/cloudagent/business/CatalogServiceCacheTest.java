package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.entity.PlatformProduct;
import com.cloudagent.business.integration.CatalogCache;
import com.cloudagent.business.repository.CatalogRepository;
import com.cloudagent.business.repository.CatalogSearchRepository;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CatalogServiceCacheTest {
  private final CatalogRepository repository = mock(CatalogRepository.class);
  private final CatalogSearchRepository search = mock(CatalogSearchRepository.class);
  private final CatalogCache cache = mock(CatalogCache.class);
  private final CatalogService service =
      new CatalogService(
          new JsonCodec(new ObjectMapper()), mock(BusinessEvents.class), repository, search, cache);

  @Test
  void listNormalizesEquivalentFiltersBeforeCaching() {
    service.products(Map.of("page", "01", "cpu", "02", "ignored", "anything"));
    service.products(Map.of("cpu", "2", "sort", "name"));
    var key = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(cache, times(2)).read(key.capture(), any());
    assertEquals(key.getAllValues().get(0), key.getAllValues().get(1));
  }

  @Test
  void skuVisibilityAndInventoryBypassDisplayCache() {
    PlatformProduct p = new PlatformProduct();
    p.active = true;
    when(repository.findProduct("p")).thenReturn(p);
    when(repository.listActiveSkus("p")).thenReturn(List.of(Map.of("available", 3)));
    assertEquals(List.of(Map.of("available", 3)), service.skus("p"));
    verifyNoInteractions(cache);
  }

  @Test
  void saveInvalidatesOnlyAfterCommitAndNotAfterRollback() {
    Map<String, Object> data =
        Map.of(
            "id",
            "p",
            "name",
            "product",
            "category",
            "ECS",
            "description",
            "description",
            "scenario",
            "test",
            "active",
            true,
            "reason",
            "test");
    for (boolean commit : List.of(false, true)) {
      TransactionSynchronizationManager.initSynchronization();
      try {
        service.saveProduct(new AuthService.Actor("admin", "ADMIN", "session"), data);
        verifyNoInteractions(cache);
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        if (commit) callbacks.forEach(TransactionSynchronization::afterCommit);
        else
          callbacks.forEach(c -> c.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
    }
    verify(cache).invalidate();
  }
}
