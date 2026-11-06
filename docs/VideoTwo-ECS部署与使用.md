# Video Two：ECS 部署与使用

部署日期：2026-11-06。源项目为 `C:\Users\Administrator\Desktop\video-two`，本轮没有修改该目录或浏览器的现有插件设置。

## 双方如何使用

房间服务器地址：

```text
wss://121.43.82.247
```

1. 双方打开已安装的“一起看”插件弹窗。
2. 将服务器地址替换为上面的 WSS 地址并保存。
3. 在支持的视频网站重新创建房间，把新房间码发给对方加入。

不需要更换插件版本、安装自签证书或继续运行本地 Node/Cloudflare Tunnel。旧服务器的房间状态没有迁移，需要重新建房。确认双方已经改用 ECS 后，可以关闭原本的本地服务和 Tunnel。

健康检查地址为 https://121.43.82.247/health 。浏览器打开 HTTPS 根路径可能返回 404，这是只有房间协议和健康检查的服务，不是视频播放网页。

视频仍由双方各自从原网站加载，ECS 只传输房间、播放、暂停、跳转和选集等状态，不下载或转发视频流。

## 实际部署

| 项目 | 实际配置 |
|---|---|
| ECS | 与现有云平台共用 `121.43.82.247`，没有新增资源或升配 |
| Node.js | Ubuntu 官方仓库 22.22.1 |
| 房间服务 | 独立系统账号 `video-two`，systemd `video-two.service` |
| 私有监听 | `127.0.0.1:8787`，不向公网开放此明文端口 |
| 房间服务限额 | MemoryMax 128MiB、V8 old space 48MiB、SwapMax 32MiB、CPUQuota 20%（0.2 CPU） |
| TLS 代理 | 独立容器 `video-two-proxy`，复用已验证的 Caddy 2.10.2 官方镜像，64MiB/0.25 CPU 限额 |
| 公网端口 | TCP 443，仅代理房间服务；没有公开平台登录 API |
| HTTPS 证书 | Let's Encrypt 受信任 IP 证书，Certbot 5.9.0，shortlived 配置 |
| 自动续期 | `video-two-cert-renew.timer` 每六小时检查一次，随机错开最多十五分钟；实际签发时机由 Certbot 决定 |

`default_sni 121.43.82.247` 处理直接按 IP 访问且不携带 SNI 的 TLS 客户端，避免云公网 NAT 的内网地址参与证书选择。

证书 HTTP-01 验证复用现有公网静态站点的 `.well-known/acme-challenge` 文件路径，没有停止或修改原平台网关路由。该站点必须保持可从公网 TCP 80 访问，`/opt/cloudagent/current/site` 应继续与静态站点目录对应。

IP 短期证书及 Certbot webroot 支持依据[Let's Encrypt 官方说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。证书由公开 CA 签发，测试没有使用 `rejectUnauthorized:false` 或跳过 HTTPS 校验。首张证书到期时间为 **2026-11-13 10:08:24（北京时间）**，已配置自动续期。

## 验收结果

- 原项目语法检查和 26 项测试通过。
- 两个独立客户端通过真实公网 WSS、正常证书校验完成建房、加入、双向拖动、播放和断线重连测试。
- 正式证书签发成功，HTTPS 健康检查返回 200。
- 证书续期 dry-run 和部署钩子演练通过；只重启视频 TLS 代理，房间 Node 主进程 PID 保持不变；之后再次进行完整公网双客户端测试通过。
- 原平台的隧道控制台、HTTP 前端预览、已有 Spring Boot 样例应用均保持 200；公网 HTTP 平台业务 API 继续返回 403，视频 HTTPS 入口上的平台 API 返回 404。
- 本轮测试后，房间服务 cgroup 约 76MiB，TLS 代理约 12MiB；主机可用约 383MiB、Swap 使用为 0。这是单次读数，没有进行大量房间或高并发压测。

这些是协议客户端与服务端检查，不表示完成两台真实浏览器的原网站播放器联机测试。双方切换地址后仍需实际观看确认网站适配及自动播放权限。

房间仍保存在 Node 内存中，双方离线三十分钟后清理；重启 Node 会丢失房间。证书续期只重载代理，已有连接短暂重连，Node 中的房间仍保留。

## 目录与维护

- 当前程序：`/opt/video-two/current`，指向独立版本目录。
- Node 单元：`/etc/systemd/system/video-two.service`。
- TLS 代理配置及续期钩子：`/opt/video-two-tools`。
- 证书与账户私钥：`/etc/letsencrypt-video-two`，仅服务器内部使用，不复制到插件或 Git。
- Node 日志：`journalctl -u video-two -f`。
- 代理日志：`docker logs --tail 100 video-two-proxy`。
- 续期记录：`journalctl -u video-two-cert-renew.service` 和 `/var/log/video-two-acme`。
- 本地部署及验收记录：`runtime/video-two/deployment.json`、`runtime/video-two/acceptance.json`。

本地复现打包/部署：

```powershell
.\.venv\Scripts\python.exe deploy\deploy_video_two.py --source "C:\Users\Administrator\Desktop\video-two"
.\.venv\Scripts\python.exe deploy\secure_video_two.py
```

只复制必要服务源码、共享网站解析器和已安装且与锁文件版本一致的纯 JavaScript `ws` 包，不复制 Android 工程、本地 Tunnel、浏览器数据或 `.runtime` 配置。更新 Node 服务会清空房间，更新前应安排双方退出。

服务器上的手动续期验证：

```sh
/opt/video-two-tools/certbot/bin/certbot renew --dry-run --run-deploy-hooks --non-interactive --no-random-sleep-on-renew --config-dir /etc/letsencrypt-video-two --work-dir /var/lib/video-two-acme --logs-dir /var/log/video-two-acme --deploy-hook /opt/video-two-tools/reload-proxy.sh
```

停用本服务时，仅操作 `video-two.service`、`video-two-cert-renew.timer` 和 `video-two-proxy`。不要清理、重启或替换现有 `cloudagent-*`、`ca-*` 容器或 MySQL。公网 443 现在属于视频同步代理，后续调整云平台 HTTPS 时需要明确合并或分配入口。
