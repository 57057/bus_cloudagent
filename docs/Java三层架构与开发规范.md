# Java 三层架构与开发规范

## 当前结构

本次将原 `platform` 包按职责拆开。Java 后端中的三层是 Controller、Service 和数据访问层；React、Java、Python 是服务分工，不是这三层。

```text
com.cloudagent.business
├─ controller   HTTP 入口（Auth / Platform / Admin / Internal / Chat）
├─ service      业务规则、权限、事务、任务处理和聊天编排
├─ repository   具名查询/更新、SQL、行锁、分页与 JDBC 执行器
├─ mapper       MyBatis-Plus Mapper
├─ entity       数据库实体
├─ dto          与 HTTP 框架无关的用例数据
├─ integration  Python Agent 客户端、支付/资源适配器
├─ job          定时触发入口
├─ web          认证过滤器、统一异常处理
├─ common       JSON、基础异常、值转换和输入验证
└─ bootstrap    迁移与部署初始化
```

## 一条下单请求

```text
POST /api/orders
  → ApiFilter 认证，将 Actor 放入当前请求
  → PlatformController.order 读取 JSON 和 Idempotency-Key
  → TradeService.createOrder 校验报价、用户额度、库存及幂等；管理事务
  → TradeRepository.lockUser / findOrderByIdempotencyKey / reserveStock / insertOrder
  → SqlExecutor 使用 JdbcTemplate 参数绑定执行
  → MySQL
```

Controller 不会直接使用 Repository。Service 可以组合多个 Repository 或其他 Service；Repository 不决定“允许谁下单”，但必须把业务层传入的用户范围落实到 WHERE 条件。用户归属和后台角色校验位于 Service，不能仅凭前端隐藏按钮。

`TradeService.owned` 复用归属用例，实际 SQL 位于 `TradeRepository.findOwned`。SQL 需要使用的动态表名在 Repository 再经过白名单检查，普通用户列表另由 `ListQueryService.userPage` 限制资源类别并绑定本人 ID。

## 本次调整

- 后台统计、模板修改、用户角色/停用、故障控制等从 AdminController 移入 AdminService；原有模板和用户修改事务跟随公开 Service 入口迁移。
- 移除后台 Controller 对用户端 Controller 的依赖，分页交给 ListQueryService 和 ListRepository。
- 地域、镜像、模拟付款、实例监控和通知已读由各自 Service 负责。
- 内部 AI 工具与支付回调的校验和审计移入 InternalToolService，接口路径及响应保持兼容。
- ChatGateway 拆成 ChatController、ChatService、ChatRepository、AgentClient；Controller 设置 SSE 头，Service 校验归属和保存消息，客户端负责 Python HTTP 协议。
- JobRunner 只触发 JobService，原有 TransactionTemplate 领取/执行/失败记录事务及供应商 REQUIRES_NEW 保留。
- 混合工具 Store 拆成 Values、JsonCodec、BusinessEvents、EventRepository 和 SqlExecutor；业务层不能提交任意 SQL。
- 原 Service SQL 提取到各业务 Repository；用户/商品 Mapper 和历史查询 Mapper 也由 Repository 包装。
- 附件在 Controller 从 MultipartFile 转为 UploadData，业务层继续验证大小、文件头和图片像素。
- 构建执行 clean，避免 Java 文件移动后旧包的 class 遗留并被 Spring 扫描。

## 事务放在哪里

`@Transactional` 放在公开 Service 用例入口，例如创建订单、取消订单、审核退款、后台修改用户和通知模板。Repository 使用当前事务，不给每条 SQL 单独开启新事务。

涉及 `FOR UPDATE` 的 Repository 方法需要在业务事务中调用才能持续持锁。事务保证共同提交/回滚，锁处理并发竞争，幂等键处理重试；这三者各有作用。

后台任务的 `TransactionTemplate` 用于显式划分领取、执行与失败记录事务。模拟供应商适配器保留独立事务，用于复现供应商已提交而商户本地尚未提交的恢复场景。不要把这些边界统一改成一个大事务。

数据库迁移是基础设施初始化，位于 bootstrap，可直接使用连接执行 DDL；MySQL DDL 的隐式提交行为不能用普通业务事务来假设。

## 开发约定和自动检查

后续开发必须遵循项目根目录 `AGENTS.md`。`ArchitectureTest` 在 Maven 测试阶段扫描主源码，检查：

1. Controller 不访问数据层/供应商、不依赖其他 Controller、不管理事务。
2. Service 不依赖 HTTP 或 ORM 查询构造细节，不内联 SQL。
3. Repository、Mapper、实体和公共数据组件不反向依赖表现/业务层。
4. REST 入口放在 controller 包。

这是可自动执行的源码约束，覆盖常见越层写法；不代替业务设计和代码审查。修改不得通过放宽检查绕过架构约定。

```powershell
.\.venv\Scripts\python.exe deploy\build_business.py
```

Service 测试覆盖迁移后的后台权限、自我锁定限制、通知用户范围、用户分页资源限制、模拟支付归属和事务注解责任。完整 HTTP 验收使用现有模拟环境脚本。

## 当前仍保留的兼容设计

现有 API 的许多输入/输出继续使用 Map，Repository 的异构 JDBC 行参数使用固定数量的具名 Object 参数，以保留原有驱动类型、空值和 JSON 字段行为。这已经隔离了层次，但编译期的数据类型约束仍弱于完整 DTO/实体设计。新增复杂用例应使用具名 DTO/record，旧接口可按业务模块逐步迁移；不能把新增 SQL 放回 Service。

本次不改表结构、HTTP 路径、支付/库存规则或 Python Agent 代码，也不启用真实支付和真实云资源。
