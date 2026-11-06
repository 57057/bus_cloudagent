# ECS Windows 前端部署与纳管

更新日期：2026-11-06。

历史部署记录：用户随后已将该ECS更换为Linux。当前单机受限演示的实际入口、部署及验收见[ECS Linux单机受限演示](ECS-Linux单机受限演示-部署验收.md)，下文IIS状态不代表当前运行环境。

## 已交付

- 访问地址：http://121.43.82.247/ 。公网首页、JS、CSS 和部署配置均已验证 HTTP 200。
- 实例：杭州现有专用 ECS，Windows Server 2022，2 核、2 GB 内存。本次没有购买新实例或重装系统。
- 本地平台登记状态为 `MANAGED_STATIC`，能力档案为 `WINDOWS_STATIC_IIS`。对应 Windows SKU 禁售、容量为 0，不参与 Linux 容器实例出租。
- Java 纳管接口 `POST /api/admin/hosts/{id}/adopt-windows-static` 仅管理员可用，通过阿里云核验实例为运行中的 Windows 主机后登记能力和审计。
- 实际使用云助手执行 PowerShell，IIS 站点及应用池名称均为 `CloudAgentFrontend`。没有安装 Linux Docker Runner。
- 只传输 React 构建静态文件；没有向 ECS 上传本地 `.env`、云密钥、数据库凭据或后端代码。
- 为站点增加 Windows 防火墙和安全组 TCP 80 入站规则；未修改既有远程桌面等规则。

## 当前边界

这是平台前端的公网预览。Java、Python、MySQL、Redis 和 Milvus 没有迁移到该 ECS，本地后端没有公开到互联网。页面有前端预览提示，登录、下单、支付和 AI 请求会明确提示后端尚未接入。

当前使用 HTTP，没有域名或 HTTPS。后续连接真实登录、支付及管理后端前，需要部署后端、配置同源 API 反向代理及 HTTPS，再关闭 `frontendOnly` 限制。

`MANAGED_STATIC` 表示已登记并通过受控脚本部署静态站点，不表示具备 Linux 容器 Runner 的心跳、租约分配或应用任务执行能力。

## 构建与受控更新

在项目根目录执行：

```powershell
npm.cmd --prefix front/platform-web run build
.\.venv\Scripts\python.exe deploy\deploy_ecs_frontend.py
```

脚本读取本地忽略的 `business-java/.env` 和 `runtime/ecs-target.json`，重新核验目标实例，再通过云助手分块上传静态包并校验 SHA-256。静态文件扩展名使用白名单；每次创建独立发布目录，切换 IIS 物理路径后检查本机 HTTP。

远端发布根目录为 `C:\ProgramData\CloudAgent\frontend\releases`。首个已验证版本为 `aef9bc95f7154ff5a4a766c1bef8ce5e`，站点目录为该版本下的 `site`。本地部署记录位于 `runtime/ecs-frontend-deployment.json`，不提交仓库。

更新脚本在健康检查失败且存在前一版本时恢复原物理路径。人工回退可在 IIS 管理器将 `CloudAgentFrontend` 的物理路径切回已验证的旧版本 `site` 目录，随后验证首页和静态资源；不要删除当前或旧发布目录来尝试恢复。

## 验证

本次 Java 构建的 47 项测试（包含三层架构检查）通过；React 生产构建通过；部署脚本 Python 语法检查通过。实际公网 HTTP 和静态资源检查通过，未将这些检查表述为登录、支付或 AI 的公网联调通过。
