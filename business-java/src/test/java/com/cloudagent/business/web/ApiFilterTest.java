package com.cloudagent.business.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ApiFilterTest {
  @Test void acceptsConfiguredPublicOriginAndRejectsLookalike() throws Exception {
    var filter = new ApiFilter(mock(AuthService.class), new JsonCodec(new com.fasterxml.jackson.databind.ObjectMapper()), "https://cloud.example.com");
    var request = new MockHttpServletRequest("GET", "/api/products");
    request.addHeader("Origin", "https://cloud.example.com");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertNotNull(chain.getRequest());
    assertEquals("https://cloud.example.com", response.getHeader("Access-Control-Allow-Origin"));
    for (String origin : new String[]{"https://cloud.example.com.attacker.test", "http://cloud.example.com", "http://localhost:5173"}) {
      request = new MockHttpServletRequest("GET", "/api/products");
      request.addHeader("Origin", origin); response = new MockHttpServletResponse(); chain = new MockFilterChain();
      filter.doFilter(request, response, chain);
      assertEquals(403, response.getStatus()); assertNull(chain.getRequest());
    }
  }
  @Test void rejectsWildcardAndPathOriginsAtStartup() {
    for (String origin : new String[]{"*", "https://cloud.example.com/login", "https://user@cloud.example.com"})
      assertThrows(IllegalArgumentException.class, () -> new ApiFilter(mock(AuthService.class), new JsonCodec(new com.fasterxml.jackson.databind.ObjectMapper()), origin));
  }
}
