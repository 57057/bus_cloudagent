# 项目开发约定

## Git 仓库布局

- 本项目统一由 `cloud_agent/.git` 管理 Java、React 和 Python；不得在 `business-java`、`front` 或 `agent` 中再次初始化嵌套仓库。
- 单独打开 `business-java` 时，IDEA 的 Git 映射指向 `$PROJECT_DIR$/..`；打开项目根目录时映射指向 `$PROJECT_DIR$`。
- 不为整理仓库自动暂存或提交用户的现有更改；构建输出、IDE 配置、运行时数据和密钥继续由根 `.gitignore` 排除。

## Java 必须遵守三层架构

适用于本目录下的所有业务开发、修复和重构。新增代码以及修改到的业务路径必须维持以下方向：

```text
controller → service → repository / mapper → MySQL
```

- `controller`：只处理 HTTP 参数、当前请求身份、Cookie、响应头和状态码，以及请求/响应转换。不得执行 SQL、注入 Repository/Mapper/JdbcTemplate/供应商适配器、依赖其他 Controller 或开启业务事务。
- `service`：负责业务校验、归属与角色权限、金额计算、状态流转、幂等和事务编排。不得依赖 Controller/Servlet/MultipartFile 或拼接 SQL。不能把来自客户端或模型的 user_id 当作认证身份。
- `repository`：按业务含义提供具名数据访问方法，封装 SQL、条件更新、行锁和聚合。不得反向依赖 Service、Controller 或 HTTP 请求。SQL 值必须绑定参数；动态表名、列名和排序必须在本层使用固定白名单。
- `mapper`：MyBatis-Plus 实体 CRUD 和自定义 SQL；由 Repository 封装访问。
- `dto` / `entity`：分别承载接口/用例数据与数据库映射，不依赖上层。新增复杂接口使用明确的请求/响应 DTO 和校验；现有 Map 接口保留兼容，但不得借此绕过分层。
- `integration`：封装 Python Agent、支付及资源供应商协议。Controller 通过 Service 调用。当前模拟供应商的独立事务用于模拟外部提交事实，不得随意改成加入商户事务。
- `job`：只负责定时触发，实际任务处理交给 Service。任务领取、租约、重试和状态修改仍通过 Repository。
- `web`：过滤器、统一异常处理和 HTTP 公共逻辑；认证调用 AuthService，不直接访问数据库。
- `common`：无业务状态的值转换、JSON 和基础异常，不得访问业务数据库或反向依赖上层。
- `bootstrap`：数据库迁移及显式演示初始化是基础设施例外，可使用 DataSource/SqlExecutor/迁移 SQL，不得作为普通业务请求的数据访问捷径。

## 保持业务一致性

- P1 本地能力及边界见 `docs/P1-本地交付与验收.md`。用户选择支付宝沙箱+阿里云，但明确先验收本地、凭据稍后填写；未完成联调的外部服务不得写成已上线。阿里云当前只有 DryRun，不能借测试创建收费实例。
- 优惠券、佣金、发票和自动续费必须沿用报价快照、实付金额、退款冲正及明确授权规则；模型只能准备预览，确认接口不可注册成 MCP 工具。
- 知识检索必须检查当前有效发布集。草稿、过期、下架文档不得通过旧缓存、旧向量或 mock_data 回退进入新回答。

- 跨表业务写入由公开的 Service 入口管理 Spring 事务；明确同类内部调用不会重新触发事务代理。
- 行锁、库存条件更新、报价/订单快照、幂等键、outbox、任务租约及资源操作版本不可在“整理代码”时删减。
- Java 决定交易及用户权限，Python 负责模型/Agent/RAG/记忆。AI 工具必须同时验证服务身份和签名用户上下文。
- 仅使用模拟支付和模拟云资源；不得把测试状态冒充真实渠道结果。
- 延续中文类/方法和关键流程注释；解释业务原因、事务边界和约束，不只复述方法名。
- 不记录密码、服务令牌、模型 Key 或完整敏感上下文，不提交 `.env` 和 `runtime` 中的凭据。

## 平台实例池与应用部署

- 当前模式为平台持有实例池、用户取得独占租约，不改回用户自带服务器方案。交付边界见 `docs/应用部署平台-本地交付与验收.md`。
- 新增POOL分配必须在订单事务中绑定具体实例，校验采购期限覆盖租期/宽限期；库存、物理资产和租约交付ID不可混为一项。
- 所有项目/部署/计划/执行结果验证当前allocation与generation。退租清理未成功不得回池；旧用户和旧任务不能影响重新分配后的实例。
- Runner仅操作明确allocation标签的批准应用容器/网络，不清理Milvus、其他容器或宿主机目录。不能把本地VM与平台基础服务共存的演示称为生产物理租户隔离。
- 模型不持有Runner凭据，不提供任意Shell或计划确认工具。运维RAG限定当前模板版本和有效发布集；模型评测结果必须区分真实故障与合成证据。
- 支付可使用已授权的模拟/支付宝沙箱链路。云采购仍关闭。用户于2026-11-06授权的专用ECS最初为Windows/IIS静态站点，随后已自行改为Linux并授权同机受限演示，以后续授权和下条范围为准。不得替用户重装系统、改变付费规格或操作其他ECS；本地后端不暴露到公网。
- 应用密钥不进入计划、工具、SSE和审计；数据库只存密文，执行时才下发必要字段。主密钥另行受控备份，不能只备份SQL后宣称可完整恢复。
- 2026-11-06后续授权覆盖旧Windows限制：用户已自行将专用ECS改为Ubuntu，明确要求控制面和应用同机，并选择2GB受限演示、允许调整依赖。可部署Java/Python/MySQL/Redis，关闭Milvus/Neo4j并显式使用已发布文档关键词检索；密钥通过已核验主机密钥的SSH私密传输。无域名时业务入口仅绑定回环并经SSH隧道访问，公网HTTP仅预览。共享主机不得宣称生产独占资源隔离，不修改付费规格、不购买新云资源。
- 用户另于2026-11-06授权部署桌面video-two的房间同步服务：现有ECS上独立video-two.service监听回环8787，video-two-proxy使用公网443和受信任IP证书；它不代理平台登录API。续期定时器为video-two-cert-renew.timer。部署与资源清理不得误停此服务，平台Runner仍只清理自己的allocation标签；后续平台HTTPS改造必须先核对443的占用。详见docs/VideoTwo-ECS部署与使用.md。

## 修改后的验证

- 在项目根目录运行 `.\.venv\Scripts\python.exe deploy\build_business.py`。构建包含 clean、编译、单元测试和 `ArchitectureTest` 分层约束。
- 新业务增加有意义的 Service/Repository 测试；不允许删除或放宽架构检查来迁就越层实现。
- 涉及订单、库存、退款或权限时，在本地模拟环境按影响运行 `tests/p0_acceptance.py`、`tests/p0_operations_acceptance.py` 等验收；测试只修改唯一命名的演示数据。
- 涉及接口和目录变更时同步更新阅读指南和交付文档；对外 API 变更必须明确兼容性。

进一步说明见 `docs/Java三层架构与开发规范.md`。
