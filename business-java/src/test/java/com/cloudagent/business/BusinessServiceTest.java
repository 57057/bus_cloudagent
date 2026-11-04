package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudagent.business.mapper.*;
import com.cloudagent.business.repository.LegacyRepository;
import com.cloudagent.business.service.BusinessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessServiceTest {
  @Test
  void deniesForeignInstanceBeforeReadingMetrics() throws Exception {
    var orders = mock(CloudOrderMapper.class);
    var instances = mock(CloudInstanceMapper.class);
    var metrics = mock(MetricsMapper.class);
    var service =
        new BusinessService(new LegacyRepository(orders, instances, metrics), new ObjectMapper());
    assertEquals(
        "error",
        service
            .execute("analyze_instance_usage", Map.of("instance_id", "foreign", "user_id", "u1"))
            .get("status"));
    verifyNoInteractions(metrics);
  }

  @Test
  void catalogAndPromotionAreCompatible() throws Exception {
    var service =
        new BusinessService(
            new LegacyRepository(
                mock(CloudOrderMapper.class),
                mock(CloudInstanceMapper.class),
                mock(MetricsMapper.class)),
            new ObjectMapper());
    assertEquals(
        "success",
        service.execute("search_product_catalog", Map.of("keyword", "GPU")).get("status"));
    assertEquals(
        "not_found",
        service
            .execute("search_product_catalog", Map.of("keyword", "nonexistent-product-xyz"))
            .get("status"));
    var data =
        (Map<?, ?>)
            service
                .execute(
                    "get_promotion_materials",
                    Map.of("product_id", "P_GPU_GN7I", "user_id", "u&other=1"))
                .get("data");
    assertTrue(data.get("exclusive_link").toString().contains("u%26other%3D1"));
  }

  @Test
  void rejectsUnboundedQueries() throws Exception {
    var service =
        new BusinessService(
            new LegacyRepository(
                mock(CloudOrderMapper.class),
                mock(CloudInstanceMapper.class),
                mock(MetricsMapper.class)),
            new ObjectMapper());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute("query_user_orders", Map.of("user_id", "u1", "limit", 100000)));
  }
}
