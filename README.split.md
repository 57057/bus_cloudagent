# 云栈：React + Java 业务平台 + Python Agent

P1 本地运营版已追加，当前体验入口为“费用与服务”和“P1运营中心”。范围、验收证据及尚未联调的外部服务见 [P1 本地交付与验收](docs/P1-本地交付与验收.md)。下文 P0 说明保留为原业务闭环基线。

需求基线见 [需求规格说明书](docs/云服务租用平台-需求规格说明书-v1.0.md)，功能、接口和验收说明见 [P0 交付说明](docs/P0-交付与验收.md)。

阅读实现可从 [Java代码阅读指南](docs/Java代码阅读指南.md) 开始；Java主源码已补充类、方法和关键流程的中文注释。

Java 已按 `Controller → Service → Repository/Mapper` 拆分，后续开发遵守 [三层架构规范](docs/Java三层架构与开发规范.md) 和根目录 [AGENTS.md](AGENTS.md)。构建时自动检查越层依赖、业务层 SQL 和 Controller 事务。

本地平台使用**模拟支付和模拟云资源**，不产生真实扣款，不申请真实云主机。MySQL 中保存的用户、报价、订单、库存、支付记录、任务、实例和售后记录是真实持久化业务数据。

```text
React + TypeScript :5173
    └─ /api → Java Spring Boot :5000 → MySQL :3307
                   └─ 登录认证 + 会话归属 → Python Agent :5001
                        └─ LangGraph / SiliconFlow / RAG / 记忆
                             └─ MCP + 服务令牌 + 签名用户上下文 → Java 受控查询
```

Java 负责全部交易、权限、库存及资源生命周期。MyBatis-Plus 用于用户/商品等实体 CRUD；条件更新、行锁、任务队列与统计使用参数化 SQL 和 Spring 事务。Python 负责模型、工具编排、检索、记忆及语义缓存，不直接访问新业务表。

## 启动

在 `D:\deepresearch\deep_research\cloud_agent` 中执行：

```powershell
# 首次安装 React 依赖
cd front\platform-web
npm.cmd ci --cache D:\deepresearch\.npm-cache
cd ..\..

# 修改 Java 后重新构建
.\.venv\Scripts\python.exe deploy\build_business.py

# 完整启动；不重复上传知识示例文档
.\.venv\Scripts\python.exe run_local.py --skip-knowledge-sync
```

入口：http://127.0.0.1:5173/ 。原 Vue 聊天页面保留在 `front/cloud_agent`，默认启动器已经改为 React。

独立业务模式不依赖 Python、模型、Milvus、Redis 或 Neo4j：

```powershell
.\.venv\Scripts\python.exe run_local.py --business-only
```

部署新版并重启已登记的 API 进程：

```powershell
.\.venv\Scripts\python.exe deploy\backup_business.py
.\.venv\Scripts\python.exe deploy\build_business.py
.\.venv\Scripts\python.exe run_local.py --restart-backend --skip-knowledge-sync
```

Java 从 `agent/.env` 的 MySQL 配置读取本地连接。`deploy/run_business.py` 将配置注入 Java 环境，并从 `runtime/releases` 运行不可变构建副本，避免 Windows 锁住 Maven 输出。

## 管理员和身份

- 普通用户在页面注册；用户名只支持小写字母、数字和下划线，密码至少10字节并包含字母与数字。
- 本地管理员由部署初始化。首次随机生成的凭据在 `runtime/platform-admin.json`，请在本地查看，不要提交该文件。修改管理员密码后，该文件不会自动覆盖数据库密码。
- 非演示部署使用 `PLATFORM_ADMIN_USER`、`PLATFORM_ADMIN_PASSWORD` 初始化管理员；不要在源码或 IDEA 共享运行配置中填入明文凭据。
- 访问令牌为数据库校验的短期不透明令牌，只在 React 内存保存；刷新令牌是 HttpOnly + SameSite=Strict Cookie，刷新及退出校验 CSRF 令牌。
- 修改密码、退出、停用账号均会撤销对应会话。Java 从认证上下文取用户身份，客户端和模型提供的 `user_id` 不能覆盖身份。
- 内部调用需要 `X-Service-Token` 与 `X-User-Context`，签名上下文同时绑定用户、登录会话、聊天会话及过期时间；服务令牌单独不能读取私人数据。

所有服务默认仅监听回环地址。对外部署需使用 HTTPS，设置 `PLATFORM_SECURE_COOKIE=true`，并将允许的来源调整为实际站点地址。

## 数据与恢复

迁移脚本在 `business-java/src/main/resources/db`，启动时持有 MySQL 命名锁并校验版本 SHA-256。已应用脚本不可修改，后续修改须追加版本。

新增业务表使用 `p0_` 前缀；原 `cloud_orders`、`cloud_instances`、`instance_metrics_daily` 保留。旧订单另存历史导入副本并核对数量与整数分总额，不伪造支付事实，不自动绑定到新账号。

```powershell
.\.venv\Scripts\python.exe deploy\backup_business.py
.\.venv\Scripts\python.exe deploy\verify_backup.py runtime\backups\<备份目录>
```

备份包含 SQL 和校验清单。恢复演练使用唯一前缀的新表，不覆盖原表；验证表保留供审查。正式恢复应先停写、在隔离数据库恢复并对账，再切换 `MYSQL_DATABASE`。回退应用使用 `runtime/releases` 的旧构建；新业务表及迁移记录不自动删除。

## 验证

```powershell
.\.venv\Scripts\python.exe deploy\build_business.py
.\.venv\Scripts\python.exe -m unittest discover -s tests -p 'test_*.py' -q
cd front\platform-web
npm.cmd run build
cd ..\..
.\.venv\Scripts\python.exe tests\p0_acceptance.py
.\.venv\Scripts\python.exe tests\p0_operations_acceptance.py
.\.venv\Scripts\python.exe tests\p0_ai_acceptance.py
```

重启恢复验收分两步：运行 `tests/p0_recovery.py prepare`，重启 Java，再运行 `tests/p0_recovery.py finish`。脚本只操作自身创建的模拟记录，通过这些记录加速租期与任务租约。验收会留下测试账号、订单和审计记录，专用测试 SKU 在正常结束时停用。

日志与本地验收报告位于 `runtime`，不会提交凭据、数据库备份、模型 Key 或依赖缓存。
