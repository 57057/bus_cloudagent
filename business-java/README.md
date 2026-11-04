# Java business / Python agent

Java 17 + Spring Boot 3 + MyBatis-Plus owns cloud_orders, cloud_instances, metrics aggregation, product catalog and promotion material rules.
Python owns LLM calls, workflow, RAG, AI-generated posters, semantic cache and conversational memory. MCP business tools delegate to Java using a shared internal token.

Public frontend -> Java :5000 /api/chat (raw SSE proxy) -> Python :5001 /api/chat.
Python MCP -> Java :5000 /internal/tools/{tool} -> MySQL.

Existing user_id/session_id contract is retained; this migration does not provide a login system. Do not expose loopback services publicly or treat client user_id as authenticated identity.
No automatic database migration/reset is performed. Existing MySQL schema is reused.
