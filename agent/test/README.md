# 手动实验入口

这些文件需要模型或数据库，不属于 `tests/` 下的离线回归测试。不要把“能执行脚本”当作业务效果验证。

| 入口 | 用途 | 当前实现 |
| --- | --- | --- |
| `python -m agent.test.milvus_rag "问题"` | 手动文档检索 | 复用 `agent.tools.vector_tool`，不维护第二套向量库补丁 |
| `python -m agent.test.graphrag_chat "问题"` | 手动图谱问答 | 复用 `agent.tools.graph_tool` |
| `python -m agent.test.test_db_tools` | 示例用户数据库查询 | 直接调用正式 MCP 工具，需要数据库 |
| `python -m agent.test.build_kg 文档路径 --write` | 实验性图谱抽取与写入 | 显式指定文档及写入选项，可能调用收费模型并修改图谱 |

正式向量入库入口是根目录 `sync_knowledge.py`。

`agent/core/graph` 保留为实验性结构化建图 API，不在网页问答链路中。网页图谱查询使用 `agent/tools/graph_tool.py`。暂不删除旧 API，以免丢失未迁移的实体模型；待有正式导入需求和验收用例后再决定接入或移除。

`Neo4jClient.clear_database()` 是破坏性维护方法，任何启动、查询、示例入口都不应自动调用。
