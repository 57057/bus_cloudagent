from typing import Callable, Awaitable
from langchain_mcp_adapters.interceptors import ToolCallInterceptor, MCPToolCallRequest, MCPToolCallResult

class UserIdInjector(ToolCallInterceptor):
    """
    拦截器：在真正调用 MCP 工具前，强制将 user_id 注入到参数中。
    """
    async def __call__(
        self,
        request: MCPToolCallRequest,
        handler: Callable[[MCPToolCallRequest], Awaitable[MCPToolCallResult]],
    ) -> MCPToolCallResult:

        # 尝试从 LangGraph 的 runtime config 中获取系统级 user_id
        if request.name not in {"query_user_orders", "query_user_instances", "analyze_instance_usage", "get_promotion_materials", "search_product_catalog", "get_promotable_products", "prepare_business_action"}:
            return await handler(request)
        user_id = None
        user_context = ""
        if hasattr(request.runtime, 'config'):
            config = request.runtime.config
            user_id = config.get("configurable", {}).get("user_id")
            user_context = config.get("configurable", {}).get("user_context", "")

        if user_id and user_context:
            new_args = dict(request.args)
            new_args["user_id"] = user_id
            # Signed by Java, never taken from model-generated tool arguments.
            new_args["user_context"] = user_context
            if request.name in {"get_promotable_products", "search_product_catalog"}:
                new_args.pop("user_id", None)
            # print(f"🔒 [安全拦截] 已强制注入 user_id={user_id} 到工具 {request.name}")
            new_request = request.override(args=new_args)
            return await handler(new_request)

        raise PermissionError("Missing user identity for protected tool")
