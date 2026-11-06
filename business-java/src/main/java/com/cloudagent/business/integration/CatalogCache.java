package com.cloudagent.business.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 只保存公开商品展示数据，值为 JSON，不保存登录身份、订单或库存事实。 使用版本键而非 KEYS/整库清空；失效前开始的查询只能写回旧版本，不会污染新版本。 */
@Component
public class CatalogCache {
  private static final Logger LOG = LoggerFactory.getLogger(CatalogCache.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final boolean enabled;
  private final long ttlSeconds;
  private final String prefix;
  private final Object[] locks = new Object[64];
  private final AtomicLong retryAfter = new AtomicLong();

  public CatalogCache(
      StringRedisTemplate redis,
      ObjectMapper json,
      @Value("${platform.catalog-cache-enabled}") boolean enabled,
      @Value("${platform.catalog-cache-ttl-seconds}") long ttlSeconds,
      @Value("${platform.catalog-cache-prefix}") String prefix) {
    this.redis = redis;
    this.json = json;
    this.enabled = enabled;
    this.ttlSeconds = Math.max(1, Math.min(3600, ttlSeconds));
    this.prefix = prefix;
    for (int i = 0; i < locks.length; i++) locks[i] = new Object();
  }

  /** 缓存旁路：未命中再查库；Redis/JSON 异常回退，但数据库业务异常必须原样传出。 */
  public Object read(String logicalKey, Supplier<Object> loader) {
    if (!enabled || System.nanoTime() < retryAfter.get()) return loader.get();
    String digest = digest(logicalKey);
    // 固定数量的本地锁合并同进程同键的冷查询，避免无界增长的锁表。
    synchronized (locks[Math.floorMod(digest.hashCode(), locks.length)]) {
      if (System.nanoTime() < retryAfter.get()) return loader.get();
      String key;
      try {
        String version = redis.opsForValue().get(prefix + "generation");
        if (version == null) {
          // 版本键被清除/淘汰后重新随机生成，不能复用固定初始版本而读到旧数据键。
          redis.opsForValue().setIfAbsent(prefix + "generation", UUID.randomUUID().toString());
          version = redis.opsForValue().get(prefix + "generation");
          if (version == null) throw new IllegalStateException("Missing cache generation");
        }
        key = prefix + version + ":" + digest;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return json.readTree(cached);
      } catch (Exception e) {
        unavailable(e);
        return loader.get();
      }
      Object fresh = loader.get(); // 不把查询失败/404当作可缓存的结果。
      try {
        // 抖动避免一批热门列表同时到期；旧版本的数据键会自行过期。
        redis
            .opsForValue()
            .set(
                key,
                json.writeValueAsString(fresh),
                Duration.ofSeconds(
                    ttlSeconds + ThreadLocalRandom.current().nextLong(1 + ttlSeconds / 5)));
      } catch (Exception e) {
        unavailable(e);
      }
      return fresh;
    }
  }

  /** 必须由 Service 在数据库事务提交后调用；失败不把已提交的商品修改报成失败。 */
  public void invalidate() {
    if (!enabled) return;
    try {
      redis.opsForValue().set(prefix + "generation", UUID.randomUUID().toString());
      retryAfter.set(0);
    } catch (Exception e) {
      unavailable(e);
    }
  }

  private void unavailable(Exception e) {
    long now = System.nanoTime();
    long previous = retryAfter.getAndSet(now + Duration.ofSeconds(5).toNanos());
    if (now >= previous)
      LOG.warn(
          "Catalog cache unavailable; using MySQL, errorType={}", e.getClass().getSimpleName());
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
