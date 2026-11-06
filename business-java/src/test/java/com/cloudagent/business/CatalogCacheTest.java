package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudagent.business.integration.CatalogCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CatalogCacheTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final ObjectMapper json = new ObjectMapper();

  private CatalogCache cache(boolean enabled) {
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("test:catalog:generation")).thenReturn("initial");
    return new CatalogCache(redis, json, enabled, 60, "test:catalog:");
  }

  @Test
  void missStoresJsonWithExpiryAndHitDoesNotLoadDatabase() throws Exception {
    CatalogCache cache = cache(true);
    Map<String, String> store = new ConcurrentHashMap<>();
    store.put("test:catalog:generation", "initial");
    when(values.get(anyString())).thenAnswer(i -> store.get(i.getArgument(0)));
    doAnswer(
            i -> {
              store.put(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    AtomicInteger calls = new AtomicInteger();
    Supplier<Object> loader = () -> Map.of("items", calls.incrementAndGet());
    Object first = cache.read("products", loader);
    Object second = cache.read("products", loader);
    assertEquals(json.valueToTree(first), json.valueToTree(second));
    assertEquals(1, calls.get());
    var ttl = ArgumentCaptor.forClass(Duration.class);
    verify(values).set(anyString(), anyString(), ttl.capture());
    assertTrue(ttl.getValue().toSeconds() >= 60 && ttl.getValue().toSeconds() <= 72);
  }

  @Test
  void unavailableRedisFallsBackAndCooldownSkipsFurtherRedisCalls() {
    CatalogCache cache = cache(true);
    when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
    assertEquals("db", cache.read("a", () -> "db"));
    assertEquals("db2", cache.read("a", () -> "db2"));
    verify(values, times(1)).get(anyString());
  }

  @Test
  void writeFailureDoesNotFailSuccessfulDatabaseRead() {
    CatalogCache cache = cache(true);
    doThrow(new IllegalStateException("offline"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    assertEquals("db", cache.read("a", () -> "db"));
  }

  @Test
  void databaseFailureIsNotSwallowedOrCached() {
    CatalogCache cache = cache(true);
    IllegalArgumentException failure = new IllegalArgumentException("not found");
    assertSame(
        failure,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                cache.read(
                    "a",
                    () -> {
                      throw failure;
                    })));
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void malformedJsonFallsBack() {
    CatalogCache cache = cache(true);
    when(values.get(anyString())).thenReturn("initial", "{broken");
    assertEquals("db", cache.read("a", () -> "db"));
  }

  @Test
  void disabledCacheDoesNotTouchRedis() {
    CatalogCache cache = cache(false);
    clearInvocations(redis);
    assertEquals("db", cache.read("a", () -> "db"));
    cache.invalidate();
    verifyNoInteractions(redis);
  }

  @Test
  void concurrentOldReadCannotRepopulateNewGeneration() throws Exception {
    CatalogCache cache = cache(true);
    Map<String, String> store = new ConcurrentHashMap<>();
    store.put("test:catalog:generation", "initial");
    when(values.get(anyString())).thenAnswer(i -> store.get(i.getArgument(0)));
    doAnswer(
            i -> {
              store.put(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString());
    doAnswer(
            i -> {
              store.put(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    CountDownLatch reading = new CountDownLatch(1), resume = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Object> old =
          executor.submit(
              () ->
                  cache.read(
                      "a",
                      () -> {
                        reading.countDown();
                        try {
                          assertTrue(resume.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        return "old";
                      }));
      assertTrue(reading.await(3, TimeUnit.SECONDS));
      cache.invalidate();
      resume.countDown();
      assertEquals("old", old.get(3, TimeUnit.SECONDS));
      assertEquals("new", cache.read("a", () -> "new"));
      assertEquals(
          json.valueToTree("new"), cache.read("a", () -> fail("should hit new generation")));
    } finally {
      resume.countDown();
      executor.shutdownNow();
    }
  }
}
