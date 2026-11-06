"""Version-scoped published runbooks. No fallback to product mocks or retired vectors."""
from langchain_core.tools import tool
from agent.tools import published_knowledge

@tool
def search_application_runbooks(query: str) -> str:
    """检索spring-react-single-host@1当前有效、已审核发布的应用部署及故障手册，返回来源引用。"""
    if not query.strip():
        return "问题不能为空。"
    try:
        return published_knowledge.retrieve(query.strip(), title_prefix="[RUNBOOK spring-react-single-host@1]")
    except Exception:
        return "当前版本的已发布运维手册不可用，不能据此确认故障原因。"
