"""Shared tool discovery. The installed adapter owns per-call MCP sessions."""
import asyncio
from functools import lru_cache
import json
import os
from pathlib import Path
import sys
from langchain_mcp_adapters.client import MultiServerMCPClient
from agent.config import get_settings
from .interceptors import UserIdInjector

ROOT = Path(__file__).resolve().parents[3]


class MCPManager:
    def __init__(self, config_path=None):
        self.config_path = Path(config_path or get_settings().mcp_servers_config)
        if not self.config_path.is_absolute():
            self.config_path = ROOT / self.config_path
        self._client = None
        self._tools = None
        self._lock = asyncio.Lock()

    def _load_config(self):
        config = json.loads(self.config_path.read_text(encoding="utf-8"))
        connections = config.get("mcpServers")
        if not isinstance(connections, dict) or not connections:
            raise ValueError("mcpServers must be a non-empty mapping")
        for connection in connections.values():
            for key in ("command", "cwd"):
                if key in connection:
                    connection[key] = connection[key].replace("{python}", sys.executable).replace("{project_root}", str(ROOT))
            # MCP stdio starts with a restricted environment; explicitly forward
            # the business-service connection settings, never model credentials.
            forwarded = {key: os.environ[key] for key in
                         ("BUSINESS_BASE_URL", "BUSINESS_SERVICE_TOKEN") if key in os.environ}
            if forwarded:
                connection["env"] = {**connection.get("env", {}), **forwarded}
        return connections

    async def connect(self):
        async with self._lock:
            if self._tools is not None:
                return
            client = MultiServerMCPClient(
                connections=self._load_config(), tool_interceptors=[UserIdInjector()])
            tools = await client.get_tools()
            names = [tool.name for tool in tools]
            if len(names) != len(set(names)):
                raise ValueError("Duplicate MCP tool names")
            self._client, self._tools = client, tools

    async def get_tools(self, allowed_names=None):
        await self.connect()
        if allowed_names is None:
            return list(self._tools)
        allowed = set(allowed_names)
        missing = allowed - {tool.name for tool in self._tools}
        if missing:
            raise RuntimeError(f"Required MCP tools unavailable: {sorted(missing)}")
        return [tool for tool in self._tools if tool.name in allowed]

    async def close(self):
        # No persistent session is opened here. Do not close in-flight tool calls.
        self._tools = None
        self._client = None


@lru_cache(maxsize=1)
def get_mcp_manager():
    return MCPManager()
