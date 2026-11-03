"""Lazy public exports: importing one component must not initialize all agents."""
from importlib import import_module

_EXPORTS = {'AgentOutput': '.workflow.state', 'AgentState': '.workflow.state', 'MCPManager': '.mcp.mcp_manager', 'AgentGraphManager': '.workflow.graph_manager', 'MemoryManager': '.memory.memory_manager'}
__all__ = list(_EXPORTS)


def __getattr__(name):
    if name not in _EXPORTS:
        raise AttributeError(name)
    value = getattr(import_module(_EXPORTS[name], __name__), name)
    globals()[name] = value
    return value
