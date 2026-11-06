# ECS Linux 单机受限演示：部署与验收

日期：2026-11-06。用户已将原 Windows ECS 更换为 Linux，并明确选择 2GB 受限演示，允许调整依赖。以下是实际部署结果，不是原两台主机完整方案的生产验收。

同日后续：另按用户授权部署了独立的[Video Two视频同步服务](VideoTwo-ECS部署与使用.md)，使用公网443的WSS与受信任IP证书；本控制台仍通过SSH隧道访问，公网平台业务API没有开放。

## 访问入口

| 入口 | 地址 | 范围 |
|---|---|---|
| 完整控制台 | http://127.0.0.1:18780/ | 本机通过已核验主机密钥的 SSH 隧道访问 ECS 回环端口 8080；可以登录、查看业务和使用 AI |
| 样例应用 | http://121.43.82.247:18080/ | 真实 ECS 上的 React + Spring Boot 容器；仅展示非敏感样例数据，未配置应用域名或 HTTPS |
| 公网前端预览 | http://121.43.82.247/ | 静态预览；公网 `/api/*`、`/internal/*` 被拒绝，不能从此明文入口登录平台 |

**127.0.0.1 只是浏览器访问隧道的入口。Java、Python、数据库、Redis、Runner 和应用都在 ECS 上运行。** 关闭电脑不会停止云端服务，但当前电脑的隧道会关闭。

云端管理员凭据保存在本地忽略文件 `runtime/ecs-linux/cloud-admin.json`，不要发到聊天或提交 Git。云端为独立新库，不沿用本地管理员密码，也没有迁移本地历史业务数据。

重开隧道，在项目根目录执行：

```powershell
.\.venv\Scripts\python.exe deploy\start_cloud_tunnel.py
```

若端口已占用，脚本不会终止未知进程；隧道日志为 `runtime/ecs-linux/tunnel.log`。浏览器使用此电脑的隧道即可，不需要导入自签根证书。

## 实际主机与组件

- 专用 ECS `i-bp17py7klx59epbxm93r`，公网 IP `121.43.82.247`，Ubuntu 26.04 x86_64，2 核 2GB，系统报告约 1735 MiB 总内存，40GB 系统盘。
- Docker Engine 29.1.3、Compose 2.40.3；没有升配、重装系统或购买新云资源。
- Docker 服务：`cloudagent-lite-business-1`、`cloudagent-lite-agent-1`、`cloudagent-lite-redis-1`、`cloudagent-lite-gateway-1`，以及当前 allocation 对应的 `ca-b-*`、`ca-f-*` 样例容器。
- MySQL 8.4.11 来自 Ubuntu 官方软件仓库，以宿主机 systemd 服务运行；仅监听 Docker 私有地址 `172.17.0.1:3306`。Java 使用独立数据库账号，Root 使用本机 Unix socket 认证，没有重置 Root 密码。
- ECS 访问 Docker Hub 超时，因此项目镜像在本地 Linux VM 使用官方基础镜像构建，再通过固定主机密钥的 SSH 传输并校验 SHA-256；没有使用不明镜像站，也没有在镜像中加入模型或支付凭据。
- 私密配置通过 SFTP 上传，未写入云助手命令内容。首次主机密钥由已认证云助手通道取得并固定；部署 SSH 私钥留在本地 `runtime/ecs-linux`。

## 2GB 取舍与实际限制

保留真实 DeepSeek 对话、MCP 业务工具、MySQL、Redis 短期会话、批准应用镜像和应用生命周期。已发布文档使用关键词检索，仍执行发布有效性及检索后的下架复核。

本机不启动 Milvus、Neo4j；不启用 Qwen 向量检索、长期向量记忆和语义缓存。完整模式默认配置仍保留，当前部署通过 `VECTOR_BACKEND_ENABLED=false`、`GRAPH_BACKEND_ENABLED=false`、`DOCUMENT_SEARCH_BACKEND=keywords` 显式选择轻量路径。

| 组件 | 内存限制 |
|---|---:|
| Java 业务 | 384 MiB，堆上限 160 MiB |
| Python Agent（含 MCP 子进程） | 512 MiB |
| MySQL systemd 服务 | 384 MiB |
| Redis | 64 MiB，数据缓存上限 32 MiB |
| Caddy 网关 | 48 MiB |
| 样例 Spring Boot | 320 MiB，堆上限 96 MiB |
| 样例 Nginx/React | 128 MiB |

配置了宿主机 2GiB Swap，仅作内存突发缓冲，不能代替物理内存或容量验证。验收结束时可用内存约 446 MiB、Swap 使用为 0；这是当时读数，不是持续负载保证。只用于低并发、单个应用槽的演示。

## 同机纳管边界

应用槽使用明确的 `SHARED_DEMO` 模式，不登记成独占物理 ECS。只有显式启用 `PLATFORM_SHARED_DEMO=true` 的环境可以登记这种资产，普通环境默认拒绝。注册能力标记共享控制面、不作为独占服务器销售。

当前演示 SKU 的计价和旧报价表单字段用于流程兼容，不代表独占 CPU、内存、磁盘或宿主机重装承诺。演示覆盖期限仅用于测试租约规则，并在纳管审计中注明不是云预付款；没有将供应商 2099 哨兵日期当作实际采购期限。

一次只允许一个有效应用分配。Runner 只操作精确 allocation 标签下的批准应用容器和网络；控制面容器、宿主机 MySQL 和其他目录不在退租清理范围。共享内核与 Docker 管理权限不构成生产租户物理隔离。

同机 Runner 使用回环控制地址 `http://127.0.0.1:8080`，安装器只在显式共享演示标记下允许这个 HTTP 特例，其他云端注册仍要求 HTTPS。Runner 使用独立凭据，不使用用户 JWT，也没有注册任意 Shell 给模型。

## 本轮实际验收

- 云端管理员登录、刷新、退出均返回 200；不受信任的 Origin 返回 403。
- 公网静态首页与资源可访问；公网业务 API 返回 403；隧道内 `/internal/tools/*` 不对外放行。
- **模拟支付**完成业务订单，真实 Runner 分配并部署应用；公网 React 页面及 `/api/hello` 均返回 200。没有把模拟支付标为支付宝付款。
- 真实容器重启、应用回滚、日志采集通过；另一测试用户查询项目或部署返回 404。
- 真实退租后原 allocation 的应用容器已清空，旧计划确认被拒绝，控制面健康保持 200；同一演示槽以更高 generation 重新分配、部署，最终应用保持运行。
- 使用单独演示审核身份通过正常流程发布版本化运维手册，没有跳过发布审批或复制本地草稿。
- 实际 `/api/chat` 经 Java → Python → DeepSeek → MCP 完成 SSE。按请求号关联的数据库审计确认调用了 `list_application_projects` 和 `get_application_evidence`。
- Python 健康接口显示 Redis 会话可用、发布文档关键词检索，Milvus、长期向量记忆、语义缓存关闭。
- Java 构建与 50 项测试通过，包括三层架构、来源白名单、默认禁止共享演示模式；14 项相关 Python 测试通过，React 生产构建通过。
- 已生成初始云端数据库备份并通过 gzip 完整性检查；**本轮未进行云端恢复演练或容量压测**。

详细证据在本地 `runtime/ecs-linux/acceptance-report.json`。演示记录在 `demo-state.json`，包含实际项目/任务编号；管理员和审核账号分别在私有凭据文件中，不在公开报告里复制密码。

支付宝沙箱凭据已在云端核验就绪并启用。本轮没有执行新的支付宝沙箱付款，付款交付验收使用明确的模拟渠道。没有域名和 HTTPS 的公网回调入口；后续沙箱实付应单独验收主动查询和回调，不宣称本轮已通过。

## 日志、持久化与维护

SSH 登录 ECS 后：

```sh
cd /opt/cloudagent/current
docker compose ps
docker compose logs --tail 100 business agent
journalctl -u mysql -u cloudagent-runner -n 100
```

部署目录由本地 `runtime/ecs-linux/deployment.json` 记录，当前路径通过 `/opt/cloudagent/current` 指向。`COMPOSE_FILE=compose.yaml:compose.native.yaml` 选择宿主机 MySQL 变体。

- 数据库目录：`/var/lib/mysql`；Redis 使用独立命名卷。
- 云端配置及应用加密主密钥：`/opt/cloudagent/private`，模型/支付凭据不进入静态文件或应用容器。
- 初始 SQL 备份：`/opt/cloudagent/private/backups`，备份具体路径在验收报告中。应用加密主密钥在 `private/secrets/application.key`，本地另有私有副本，必须与 SQL 分开保管。
- Runner 配置和回执：`/var/lib/cloudagent-runner`，服务源文件在 `/opt/cloudagent-runner`。升级时不得清空注册身份或 SQLite 回执。
- Docker、MySQL、Runner 已启用开机启动；容器设置 `unless-stopped`。本轮验证了配置，没有声称完成整机重启演练。

恢复已暂存版本的容器服务：

```powershell
.\.venv\Scripts\python.exe deploy\deploy_ecs_lite.py --resume
```

`--resume` 不重建镜像、不改数据库、不重新生成凭据。更新代码需先在 Linux 构建环境重建对应镜像，再用 `deploy/transfer_cloud_images.py` 传输批准镜像；不能只更新源码目录就声称运行版本已改变。初次暂存可用 `--stage-only`，离线镜像已就绪可用 `--offline-images`。

不要执行 `docker compose down -v`、全局 Docker prune 或删除 MySQL/Runner 目录。应用回滚走平台已确认计划；平台版本回退需要同时核对镜像、Compose 配置和数据库迁移兼容性。

## 后续正式上线工作

申请/配置域名和可信 HTTPS 后，才开放公网业务入口、使用 Secure Cookie 并关闭前端预览限制。应用若传递敏感凭据，也要增加应用 HTTPS。

恢复 Milvus/Qwen、Neo4j 和向量记忆前需要提升容量并实际验证。正式用户服务还需独立隔离策略、沙箱实付/正式支付验收、自动备份恢复、监控告警和容量测试；当前结果仅为明确授权的单机受限演示。

## 2026-11-06 登录超时故障恢复

用户在云端控制台登录一直“处理中”。排查时，公网HTTP、视频WSS和SSH均无响应，云助手返回ClientNotRunning，实例仍为Running；故障期间磁盘读取持续约112MB/s。控制台截图及上一轮启动的内核日志确认，当日18:26:49曾因OOM杀死UID999的Runner python3进程，匿名内存约501MiB。该日志证明已存在Runner内存失控风险，不将它误写成用户点击登录的精确故障时间。

本轮执行正常重启（ForceStop=false），没有强制断电、重装或修改付费规格。核实UID999为cloudagent-runner后，完成这些修复：

- Runner每线程复用独立HTTP opener，避免每次轮询都创建默认HTTPSHandler及TLS上下文；保留禁用环境代理的要求。
- Runner配置MemoryHigh=80MiB、MemoryMax=128MiB、MemorySwapMax=64MiB、CPUQuota=20%，并提高其OOM牺牲优先级。此前遗漏了这个组件的内存上限。
- 登录和刷新请求15秒超时，普通JSON接口30秒超时；计时覆盖响应体读取。错误可见后解除忙碌状态；SSE使用原有独立流式逻辑。
- SSH转发通道打开最多等待10秒，失败时尽量返回502 JSON；使用`deploy/start_cloud_tunnel.py --restart`重建已记录且验证过进程身份的隧道。
- 发现样例容器原先没有自动恢复策略，已为本租约容器设置unless-stopped，并通过平台确认的RESTART恢复。后续Runner部署也写入此策略；手动停止的应用仍保持停止语义。

恢复后管理员登录实测HTTP200、约0.2秒，刷新会话200，云端前端产物哈希与本次构建一致。Runner短时观测约26MiB、峰值约35MiB，整机可用约580MiB、Swap使用0，内存压力短时平均为0；这不是长期容量压测结论。视频公网双客户端建房/双向同步/重连再次通过，样例应用HTTP200。

网络超时回归3项、Runner回归6项及React构建通过。证据在`runtime/ecs-linux/login-recovery.json`，OOM截图也在该私有运行目录。浏览器需Ctrl+F5加载新的超时处理；云端默认管理员仍为cloudadmin，凭据文件为`runtime/ecs-linux/cloud-admin.json`。整机重启清空了视频房间，需要重新建房。
