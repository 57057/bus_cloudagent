import os
import json
import requests
import sys
import base64
from mcp.server.fastmcp import FastMCP
from agent.config import get_settings

# ==============================================================================
# 初始化环境配置
# ==============================================================================

# ==============================================================================
# 初始化 FastMCP 服务器
# 这个 Server 可以独立运行，支持 SSE 或 stdio 协议
# ==============================================================================
mcp = FastMCP("CloudPlatformMCPServer")

def _business_call(name, arguments, user_context=""):
    """MCP transport adapter; business rules and SQL live in Spring Boot."""
    token = os.getenv("BUSINESS_SERVICE_TOKEN", "")
    if not token:
        return json.dumps({"status":"error","message":"Java业务服务令牌未配置"},ensure_ascii=False)
    try:
        payload = user_context.split(".")[0]
        try:
            context_data = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        except (ValueError, TypeError):
            context_data = {}
        response = requests.post(os.getenv("BUSINESS_BASE_URL","http://127.0.0.1:5000")+"/internal/tools/"+name,
            json=arguments, headers={"X-Service-Token":token,"X-User-Context":user_context,
                                    "X-Request-Id":context_data.get("request_id", "")}, timeout=20)
        if not response.ok:
            return json.dumps({"status":"error","message":"业务服务请求失败","code":response.status_code},ensure_ascii=False)
        return json.dumps(response.json(),ensure_ascii=False)
    except requests.RequestException:
        return json.dumps({"status":"error","message":"Java业务服务暂不可用"},ensure_ascii=False)



# ==============================================================================
# 数据库连接辅助函数
# ==============================================================================

# ==============================================================================
# MCP 核心工具定义 (Tools)
# ==============================================================================

# 模拟的云产品目录数据库 (基于 mock_data 真实文档)

@mcp.tool()
def list_application_projects(user_id: str = "", user_context: str = "") -> str:
    """列出当前用户的应用项目，获取真实项目编号；不能查看其他用户。"""
    return _business_call("list_application_projects", {}, user_context)

@mcp.tool()
def get_application_evidence(project_id: str, user_id: str = "", user_context: str = "") -> str:
    """读取本人有效租约中项目的最近部署、健康和已采集的脱敏运行证据；没有证据不能推测。"""
    return _business_call("get_application_evidence", {"project_id": project_id}, user_context)

@mcp.tool()
def prepare_application_action(project_id: str, kind: str, target_deployment_id: str = "",
                               user_id: str = "", user_context: str = "") -> str:
    """准备DEPLOY/RESTART/ROLLBACK/LOGS计划，不执行；必须由用户在网页核对后确认。"""
    return _business_call("prepare_application_action", {"project_id": project_id,"kind": kind,
                          "target_deployment_id": target_deployment_id or None}, user_context)

@mcp.tool()
def prepare_business_action(action: str, parameters: dict, user_id: str = "", user_context: str = "") -> str:
    """准备购买PURCHASE、续费RENEWAL或退款REFUND预览；不会下单、付款或提交退款。必须由用户在网页确认。"""
    return _business_call("prepare_business_action", {"action": action, "parameters": parameters}, user_context)

@mcp.tool()
def get_promotable_products(user_context: str = "") -> str:
    """
    当用户说“我想推广商品”、“我想赚钱”、“有哪些商品可以推广”时调用。
    获取系统当前所有支持推广、返佣的产品列表。
    """
    return _business_call('get_promotable_products', {}, user_context)

@mcp.tool()
def search_product_catalog(keyword: str, user_context: str = "") -> str:
    """
    根据用户的自然语言描述（如“云服务器”、“2核4G”、“GPU”），模糊搜索并返回符合条件的产品信息及【产品ID】。
    
    Args:
        keyword: 用户描述的产品关键词。
    """
    return _business_call('search_product_catalog', {'keyword': keyword}, user_context)

@mcp.tool()
def get_promotion_materials(product_id: str, user_id: str = "", user_context: str = "") -> str:
    """
    根据【产品ID】获取对应的专属推广链接和返佣活动信息。
    必须先调用 search_product_catalog 获得精确的 product_id 后再调用此工具。
    
    Args:
        product_id: 必须是标准的产品 ID，如 "P_ECS_G8A_XLARGE", "P_GPU_GN7I" 等。
        user_id: [系统注入] 当前用户的ID，用于生成专属的带参数返佣推广链接。
    """
    # 模拟从后台营销系统中获取的物料数据 (主键为 product_id)
    return _business_call('get_promotion_materials', {'product_id': product_id, 'user_id': user_id}, user_context)

@mcp.tool()
def generate_ai_poster(prompt: str) -> str:
    """
    调用千问文生图模型 qwen-image-2.0，根据提示词生成竖版推广海报。
    
    Args:
        prompt: 详细的生图提示词（如：赛博朋克风格的服务器机房，炫酷的蓝色霓虹灯，科技感，竖屏海报风格）。
    """
    api_key = get_settings().dashscope_api_key
    if not api_key:
        return json.dumps({"status": "error", "message": "未配置 DASHSCOPE_API_KEY"}, ensure_ascii=False)

    url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}"
    }
    payload = {
        "model": "qwen-image-2.0",
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [{"text": prompt}]
                }
            ]
        },
        "parameters": {
            "negative_prompt": "低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，文字模糊，构图混乱",
            "prompt_extend": True,
            "watermark": False,
            "size": "1536*2688"
        }
    }

    last_error = "生成失败"
    for attempt in range(1, 3):
        try:
            sys.stderr.write(f"[AI-POSTER][QWEN] attempt={attempt} submit start\n")
            res = requests.post(url, json=payload, headers=headers, timeout=120)
            data = res.json()
            request_id = data.get("request_id", "")
            sys.stderr.write(f"[AI-POSTER][QWEN] attempt={attempt} status={res.status_code} request_id={request_id}\n")

            image_url = (
                data.get("output", {})
                .get("choices", [{}])[0]
                .get("message", {})
                .get("content", [{}])[0]
                .get("image")
            )
            if res.status_code == 200 and image_url:
                sys.stderr.write(f"[AI-POSTER][QWEN] attempt={attempt} success\n")
                return json.dumps({
                    "status": "success",
                    "data": {
                        "poster_url": image_url,
                        "message": "海报生成成功（Qwen-Image）",
                        "request_id": request_id
                    }
                }, ensure_ascii=False)

            last_error = data.get("message") or data.get("code") or f"HTTP {res.status_code}"
            sys.stderr.write(f"[AI-POSTER][QWEN] attempt={attempt} failed: {last_error}\n")
        except Exception as e:
            last_error = str(e)
            sys.stderr.write(f"[AI-POSTER][QWEN] attempt={attempt} exception: {last_error}\n")

    return json.dumps({"status": "error", "message": f"Qwen-Image 生成失败: {last_error}"}, ensure_ascii=False)

@mcp.tool()
def query_user_orders(user_id: str, limit: int = 5, user_context: str = "") -> str:
    """
    查询用户的云服务器订单和账单记录。
    
    Args:
        user_id: [系统注入] 用户的唯一标识符，不允许模型伪造。
        limit: [模型生成] 返回的最大记录数，默认为 5。
    """
    return _business_call('query_user_orders', {'user_id': user_id, 'limit': limit}, user_context)

@mcp.tool()
def query_user_instances(user_id: str, limit: int = 5, user_context: str = "") -> str:
    """
    查询指定用户的服务器实例状态，返回实例ID、规格、公网IP、运行状态等信息。
    必须传入系统注入的 user_id。
    """
    return _business_call('query_user_instances', {'user_id': user_id, 'limit': limit}, user_context)

@mcp.tool()
def analyze_instance_usage(instance_id: str, user_id: str = "", user_context: str = "") -> str:
    """
    根据实例ID，获取该实例过去 7 天的平均 CPU 利用率、内存利用率和峰值带宽。
    常用于架构诊断或成本优化 (FinOps) 场景，帮助判断资源是否闲置。
    
    Args:
        instance_id: 服务器实例的唯一ID，如 "i-bp1abcdefg"。必须先通过 query_user_instances 查出。
        user_id: [系统注入] 当前用户的ID，用于安全鉴权，防止越权查询他人监控数据。
    """
    return _business_call('analyze_instance_usage', {'instance_id': instance_id, 'user_id': user_id}, user_context)

# ==============================================================================
# 服务启动入口
# ==============================================================================
if __name__ == "__main__":
    import sys
    sys.stderr.write("🚀 正在启动 Cloud Platform MCP Server (stdio 模式)...\n")
    # FastMCP 默认通过标准输入/输出(stdio)与大模型 Agent 通信
    mcp.run()
