package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cloudagent.business.common.*;
import com.cloudagent.business.dto.ApplicationRequests.Host;
import com.cloudagent.business.repository.PoolRepository;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SharedDemoTest {
  @Test void sharedControlPlaneCannotBeRegisteredInNormalEnvironment() {
    var repository = mock(PoolRepository.class);
    var service = new PoolService(repository, new JsonCodec(new ObjectMapper()), mock(BusinessEvents.class));
    var error = assertThrows(ApiError.class, () -> service.add(new AuthService.Actor("admin", "ADMIN", "session"),
        new Host("ecs:demo-slot", "demo", "Shared demo", "127.0.0.1", "2030-01-01T00:00:00Z", "SHARED_DEMO")));
    assertEquals("SHARED_DEMO_DISABLED", error.code);
    verifyNoInteractions(repository);
  }
}
