# Java 商品目录缓存

Java 使用 Spring Data Redis 的 StringRedisTemplate，将公开展示数据序列化为 JSON。
参考：[Spring Data Redis 官方说明](https://docs.spring.io/spring-data/redis/reference/redis/template.html)。

## 范围和分层

- 商品列表：包括筛选、排序、分页和总数；查询参数先校验并归一化，键中只保存 SHA-256 摘要。
- 商品详情、地域选项：同样采用先读缓存、未命中查 MySQL 并回填的方式。
- SKU 可售库存、商品可见性检查、报价、下单、付款、退款、登录权限均继续直接使用 MySQL。展示缓存不参与交易决策。
- Controller → CatalogService → Repository 查询数据库；CatalogService 调用 integration/CatalogCache 访问 Redis，不在 Controller 中操作缓存。
- 与 Python 的记忆、语义缓存共享 Redis 实例时，仅使用独立业务键前缀，不执行 FLUSHDB、FLUSHALL 或 KEYS 全量扫描。

## 一致性与降级

商品/SKU 更新事务提交后切换 generation 版本，列表、详情及地域缓存一起失效；事务回滚不失效。旧查询即使在修改完成后回填，也只能写进旧版本；旧键按 TTL 自动删除。

默认 TTL 为60秒，另加0～12秒随机抖动。本进程通过固定数量的锁合并同键冷查询；这不是跨实例分布式锁，也没有承诺全局只回源一次。

Redis 连接/命令超时设置为300ms。连接异常、写入失败或缓存 JSON 损坏时回退 MySQL，触发5秒缓存访问冷却并记录不含密钥的告警。数据库异常仍正常向上抛出，不会被缓存吞掉。

Redis 故障期间的商品修改仍可成功。若失效通知失败，Redis 恢复后可能短暂读到修改前的展示值，最长为其剩余 TTL；默认上限72秒。报价、下单和 SKU 库存仍实时查库，所以旧展示值不能绕过交易校验。多实例部署必须使用相同前缀；不同业务数据库必须使用不同前缀。

## 本地配置与运行

配置模板见 business-java/config.example.env：REDIS_HOST、REDIS_PORT、REDIS_USERNAME、REDIS_PASSWORD、REDIS_SSL、BUSINESS_REDIS_DATABASE、CATALOG_CACHE_ENABLED、CATALOG_CACHE_TTL_SECONDS、CATALOG_CACHE_PREFIX。启动脚本默认复用 agent/.env 的 REDIS_URL（含密码、数据库及TLS），Java 独立连接变量可覆盖它；凭据只经子进程环境传递，不输出。IDEA 直接运行时默认连接127.0.0.1:6379、数据库0，需手动填写密码等变量。业务键前缀为 cloudagent:business:catalog:v1:。TTL 限制1～3600秒。

项目 run_local.py 的完整模式和 --business-only 模式均会启动本地 Redis。IDEA 直接运行 Java 时需自行确保 Redis 已启动，并在运行配置填写非默认连接环境变量；Redis 不可用时仍可查库。

可用 redis-cli 的 SCAN 查看此前缀的键，再用 GET/PTTL 查看 JSON 和剩余有效期。不要清空整个 Redis，以免影响 Python 记忆。

## 验证入口

- deploy/build_business.py：包含缓存命中、JSON/连接/写入故障降级、数据库异常传递、旧查询回填竞争、筛选键归一化、SKU 绕过缓存、提交/回滚失效测试及三层架构检查。
- tests/catalog_cache_acceptance.py：使用真实 HTTP、Redis 和 MySQL，验证缓存命中、后台修改失效、下架、TTL，以及回滚不失效。仅创建唯一命名的模拟产品/SKU。

2026-11-04 本地验证：Java clean 构建37项测试全部通过；真实 HTTP/Redis/MySQL 的5组缓存验收通过；P0交易回归12组通过（包含100并发竞争10库存）。Redis故障降级由单元测试注入连接/写入异常验证，本次未停止共享Redis做故障演练，也未进行容量压测。报告位于 runtime/catalog-cache-acceptance.json 和 runtime/p0-acceptance.json。
