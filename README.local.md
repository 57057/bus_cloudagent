> 当前 P0 平台入口与说明见 [README.split.md](README.split.md)。下文为 Python 模块调试记录；网页通过 Java 登录网关访问，长期偏好默认关闭，内部接口不再接受未认证身份。

# Cloud Agent 本地运行

项目目录：`D:\deepresearch\deep_research\cloud_agent`

## Python 导入与单独调试

项目现在使用与磁盘一致的标准包名：`agent.core`、`agent.config`、`agent.agents`、`app.service` 等。项目根目录是包的共同父目录，不能再把 `agent` 和 `app` 当作两个独立源码根目录。旧的 `from core...`、`from config...` 等导入已迁移，不依赖 IDE 识别 setuptools 自定义包映射。

PyCharm 请选择项目的 `.venv\Scripts\python.exe`，在项目根目录执行一次：

```powershell
.\.venv\Scripts\python.exe -m pip install -e . --no-deps --no-build-isolation
```

项目通过 `pyproject.toml` 注册可编辑安装，不再依赖 IDE 临时添加 Sources Root。换解释器或重建虚拟环境后需要重新安装；第三方依赖仍使用现有 requirements 文件安装。

根目录运行示例：

```powershell
.\.venv\Scripts\python.exe -X utf8 -m agent.main
.\.venv\Scripts\python.exe -X utf8 -m agent.agents.product_agent
.\.venv\Scripts\python.exe -X utf8 -m agent.agents.billing_agent
.\.venv\Scripts\python.exe -X utf8 -m agent.core.workflow.graph_manager
.\.venv\Scripts\python.exe -X utf8 -m agent.core.graph.parser
```

演示可能调用真实模型或数据库，需要相应配置。没有 main 的类定义文件不会自动开始测试。绝对导入通过可编辑安装解决；图谱解析器的直接文件运行另补齐了相对导入上下文。公共包使用惰性导出，避免导入一个模块时连带导入全部 Agent 产生循环依赖。

## 长期偏好保存

网页与交互 CLI 按会话积累尚未处理的用户消息，普通对话每五轮提取一次；检测到“请记住”“以后默认”等明确表达时立即触发，连同当前未处理消息一起分析。临时要求不会触发即时提取，提取提示词也要求排除当前任务要求。同一用户的网页任务按顺序执行，单次提取最多等待 60 秒。只分析新增消息，已经提交提取的批次不再从完整历史中重复读取；长期偏好仍按用户保存、跨会话加载。

网页可调用 `POST /api/chat/session/end`，请求体含 `user_id`、`session_id`，补提取该会话剩余消息；返回 accepted 仅代表已受理，不代表已经持久化。关闭标签页不保证触发此接口。正常关服会补提取所有剩余批次；CLI 退出时只处理剩余消息。进度缓冲在当前进程内，不是持久化任务队列；重启或强制退出可能丢失未处理内容。明确表达识别采用轻量规则，不保证覆盖所有自然语言说法。

Redis 不可用时仍可使用进程内快照。有效模型配置和 Milvus 可用时，偏好嵌入后写入长期记忆集合，后续请求按用户过滤加载。

提示词要求不保存密码、密钥及临时业务数据，但不是完备的敏感信息检测。精确重复写入前检查，近似去重仍由既有提取逻辑处理，不提供跨进程原子去重或偏好冲突自动合并。正常关服等待任务完成，强制终止进程可能丢失未完成任务。

提取和嵌入会调用供应商接口。自动化测试采用替身验证流程，不调用真实收费接口。

## 模型配置

### MCP、缓存与实验入口

所有业务 Agent 通过 `agent.core.mcp.MCPManager` 的共享实例发现工具，配置位置由 `MCP_SERVERS_CONFIG` 统一指定。默认配置中的 `{python}` 和 `{project_root}` 会解析为当前解释器与项目根目录，搬动项目后无需手改绝对路径。各 Agent 仍只拿到自己的工具白名单；缺少必需工具会明确失败并允许下次重试。

身份注入位于 `agent/core/mcp/interceptors.py`。只有订单、实例、监控和专属推广工具接收用户 ID，缺少身份时拒绝执行；公共查询和生图工具不注入无效参数。这不替代登录认证，目前 HTTP 用户身份仍需要后续完善。

适配器为每次工具调用创建并管理独立会话；共享的是发现结果，不是一个永久运行的 MCP 会话。关服清空发现缓存，不会伪装成关闭所有外部进程。

**缓存读取恢复原来的分级顺序：当前用户精确匹配 → 公共精确匹配 → 公共及当前用户范围内的语义匹配 → Agent。** 语义相似度阈值保持 0.92。读取不再因存在对话历史或偏好而一律跳过缓存。

**写入采用“高频＋通用”自动准入，不需要维护者确认。**

- 每个请求在查询缓存之前，通过 Redis Lua 原子计数；即使尚无缓存也统计。问题统一大小写、首尾与重复空白后计数，不做语义聚合。默认自首次请求开始的固定 24 小时窗口内达到 3 次；不是每个请求重置计数窗口，也不是滚动时间窗口。
- 达到门槛后，产品咨询节点执行成功、实际文档检索提供来源，且没有使用历史/偏好上下文、没有明显个人/动态/指代/选型要求时，自动将本次通用回答写入公共缓存。模型自行编写的引用不能作为来源证据。账单、监控、推广、失败回答不写入。准入采用保守规则，并不等同于完整的事实核验或敏感信息识别。
- Redis 不可用时返回零计数，不编造热度，因此跳过自动写入；已有缓存读取和聊天仍可工作。计数连接在服务启动时初始化，恢复 Redis 后需要重启后端。
- 每次有效的精确或语义命中，都将过期时间更新为 `min(命中时间＋滑动有效期, 首次生成时间＋最长存活期)`。不重新生成向量，不重置首次生成时间。默认滑动有效期 24 小时、最长存活 7 天，过期记录不能通过命中复活。续期失败只记录日志，不丢弃当前有效答案。

```dotenv
CACHE_MIN_REQUESTS=3
CACHE_FREQUENCY_WINDOW=86400
CACHE_SLIDING_TTL=86400
CACHE_MAX_AGE=604800
```

新结构使用 `qa_hot_v2_*` 集合，保留范围隔离，并增加首次生成、绝对到期和滑动有效期字段。旧集合不删除、不自动迁移。文档内容哈希、嵌入配置和 `SEMANTIC_CACHE_REVISION` 共同隔离知识版本；文档发布后重启后端以加载新版本。过期是读取过滤失效，不代表立即物理删除。

旧 `app.preload_cache` 人工审批预热入口已退役，不会绕过热度门槛。无需先制作或审批 FAQ 文件。命中续期依赖当前 Milvus 2.6 的部分更新功能。

旧图谱建设 API 的定位与手动实验命令见 `agent/test/README.md`。向量和图谱查询演示已复用正式工具；图谱写入实验必须显式指定 `--write`。

聊天和嵌入都使用硅基流动的 OpenAI 兼容接口：

```dotenv
SILICONFLOW_API_KEY=你的硅基流动密钥
MODEL=deepseek-ai/DeepSeek-V3.2
BASE_URL=https://api.siliconflow.cn/v1
EMBEDDING_MODEL=Qwen/Qwen3-Embedding-4B
EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
EMBEDDING_DIMENSIONS=1536
```

在 `agent/.env` 填写 `SILICONFLOW_API_KEY` 后执行：

```powershell
cd D:\deepresearch\deep_research\cloud_agent
.\start.cmd --restart-backend
```

该命令会启动 Milvus、用 Qwen 对 `mock_data/*.md` 分块并建立向量索引，再重启后端。文档按内容哈希去重，重复执行不会重复写入已有片段。更换嵌入模型、端点或维度时使用新的集合，不覆盖旧向量。

Key 未填写时，页面和数据库可运行，聊天会返回 503 和明确的配置提示；本地关键词检索保留，长期向量记忆和语义缓存等待有效 Key。当前 Key 已配置，24 个产品文档片段已成功通过 Qwen 嵌入并入库；DeepSeek 真实检索问答与 SSE 返回均已验证。

官方接口说明：[对话](https://docs.siliconflow.cn/docs/userguide/capabilities/text-generation)、[嵌入和可选维度](https://docs.siliconflow.cn/docs/api/embeddings-post)。

## 服务地址

| 服务 | 地址 | 用途 |
| --- | --- | --- |
| Vue 前端 | http://127.0.0.1:5173/ | 聊天界面 |
| FastAPI | http://127.0.0.1:5000/docs | API 文档 |
| 健康状态 | http://127.0.0.1:5000/health | 模型配置和数据库状态 |
| Redis | 127.0.0.1:6379 | 短期会话记忆 |
| MySQL | 127.0.0.1:3307 | 示例账单、实例和监控指标 |
| Neo4j | http://127.0.0.1:7474/ | 产品知识图谱，188 个节点 |
| Milvus | 127.0.0.1:19530 | 产品向量、长期记忆、语义缓存 |
| Milvus 管理界面 | http://127.0.0.1:9091/webui/ | 数据库运行状态 |

数据库连接和密码保存在 `agent/.env`，该文件已排除版本控制。MySQL 使用独立的 `runtime/mysql-data`，Neo4j 位于 `runtime/neo4j-community-5.26.0`。现有 `D:\java` Java 17 和 `D:\mysql-5.7.24-winx64` 提供程序文件，应保留这两个目录。

## Docker 和 Milvus

当前 Windows LTSC 19044 缺少 WSL/Hyper-V，Docker Engine 运行在本机 VirtualBox Ubuntu 24.04 虚拟机中。已安装 VirtualBox 7.2.20；虚拟机名 `CloudAgent-Docker`，4 个虚拟 CPU、4GB 内存、25GB 动态磁盘。虚拟机文件位于 `runtime/docker-vm/`，Ubuntu 镜像和安装包已核对官方校验值。

Milvus 2.6.17 使用官方支持的单容器模式，内嵌 etcd、本地持久化存储。数据保存在 VM 内 `/home/cloudagent/milvus/volumes/milvus`，底层由 `runtime/docker-vm/ubuntu.vdi` 保存。配置源位于 `deploy/milvus.embedded.yml`、`deploy/embedEtcd.yaml`、`deploy/milvus.user.yaml`。

Windows 侧只映射本机回环地址：SSH 2222、Milvus 19530、管理端口 9091。Docker Engine 不开放 TCP 管理端口，管理命令使用 SSH 密钥。

```powershell
# 查看 Docker 版本和容器
.\docker.cmd version
.\docker.cmd ps

# 查看 Milvus 日志
.\docker.cmd logs --tail 50 milvus-standalone
```

普通启动命令：

```powershell
.\start.cmd
```

VM 会按需启动，Milvus 容器具有 `unless-stopped` 重启策略。应用后台日志在 `runtime/logs/`，VM 串口日志在 `runtime/docker-vm/serial.log`。Docker 镜像下载复用本机现有代理 `127.0.0.1:10808`，在 VM 中对应 `10.0.2.2:10808`；已下载的容器运行不依赖代理。Docker 的代理配置在 VM 内 `/etc/systemd/system/docker.service.d/proxy.conf`。

官方部署依据：[Milvus 单容器安装](https://milvus.io/docs/v2.6.x/install_standalone-docker.md)。官方多容器 Compose 所依赖的 MinIO 镜像无法匿名拉取，因此采用同版本的官方内嵌部署配置。

## 验证

- Docker Engine 已运行，Milvus 容器健康检查通过。
- Windows Python SDK 可连接 Milvus，1536 维向量写入、搜索和用户过滤通过。
- Milvus 容器重启后的数据保留已验证，临时验证集合已清理。
- 后端确认 Milvus 已连接、Redis 短期记忆可用。
- Qwen 产品文档索引已生成：24 个唯一片段，1536 维；后端长期记忆和语义缓存均已启用。
- 硅基流动 DeepSeek 已通过真实 VPC 检索问答，答案引用 `ecs_network_security.md` 和 `rds_product_info.md`，SSE 正常完成。
- 硅基流动聊天、Qwen 嵌入请求格式和语义缓存阈值测试通过；测试未调用真实收费接口。

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

应用依赖锁定在 `requirements.local.lock.txt`。VM 管理脚本额外依赖 `paramiko` 和 `pycdlib`，均安装在项目虚拟环境。

若只需要重启本地服务而不调用文档嵌入接口，使用 `start.cmd --restart-backend --skip-knowledge-sync`。

用户已明确授权将项目自带的 `mock_data` 示例文档发送至硅基流动，用于 Qwen 嵌入和 DeepSeek 检索问答。
