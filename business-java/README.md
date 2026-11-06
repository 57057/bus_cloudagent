# Java业务服务

Java 17 + Spring Boot 3 + MyBatis-Plus负责登录、权限、商品、交易、支付、资源租约、售后、运营和平台实例池/应用部署任务。遵守Controller → Service → Repository/Mapper三层架构。

Python负责模型、Agent、RAG与记忆；独立Linux Runner执行平台批准的部署模板。模型和前端传入的user_id不能代替认证身份。MCP验证服务身份和Java签名用户上下文，Runner使用独立主机凭据。

React → Java :5000 → Python :5001；MCP → Java受控工具。项目包含登录系统，启动时执行版本化迁移并校验已应用脚本的校验和，不删表重建。

从项目根目录构建和运行：

```powershell
.\.venv\Scripts\python.exe deploy\build_business.py
.\.venv\Scripts\python.exe run_local.py --skip-knowledge-sync
```

通过启动脚本读取agent/.env中的数据库配置和business-java/.env中的外部接入配置；IDEA直接运行时须配置相应环境变量。密钥不入Git，不在日志输出。

实际实现、界面、测试及外部边界见[平台交付说明](../docs/应用部署平台-本地交付与验收.md)、[P1交付说明](../docs/P1-本地交付与验收.md)与[Java开发规范](../docs/Java三层架构与开发规范.md)。目前可用本地Linux实例演示；真实ECS纳管和公网生产部署尚未验收。
