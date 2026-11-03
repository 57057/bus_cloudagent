"""Manual integration demo; importing this module does not call paid APIs."""
import asyncio
from agent.agents.product_agent import ProductAgentNode


async def main():
    node = ProductAgentNode()
    for query in (
        "ecs.g8a.4xlarge 能挂载多少块弹性网卡？",
        "五天无理由退款有什么限制条件？",
        "什么是专有网络 VPC？",
    ):
        result = await node({"messages": [("user", query)], "memory_context": ""})
        print(result["messages"][-1].content)


if __name__ == "__main__":
    asyncio.run(main())
