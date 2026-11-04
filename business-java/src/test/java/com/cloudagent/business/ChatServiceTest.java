package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudagent.business.common.*;
import com.cloudagent.business.integration.AgentClient;
import com.cloudagent.business.repository.ChatRepository;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 使用内存上游验证拆分后的 SSE，不调用真实模型或发送用户内容。 */
class ChatServiceTest {
  private final ChatRepository repository = mock(ChatRepository.class);
  private final TradeService trade = mock(TradeService.class);
  private final AgentClient client = mock(AgentClient.class);
  private final AuthService.Actor actor = new AuthService.Actor("u1", "USER", "auth1");
  private final ChatService service =
      new ChatService(new JsonCodec(new ObjectMapper()), repository, trade, client);

  @SuppressWarnings("unchecked")
  private void upstream(String data) throws Exception {
    when(trade.owned("chat_sessions", "chat1", "u1", false)).thenReturn(Map.of("deleted", false));
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    when(client.openChat(actor, "chat1", "问题")).thenReturn(response);
    when(repository.countActiveSessions("chat1")).thenReturn(1L);
  }

  @Test
  void completeStreamPreservesFramesAndArchivesAnswer() throws Exception {
    String events = "data: {\"content\":\"你好\"}\n\ndata: {\"done\":true}\n\n";
    upstream(events);
    var stream =
        service.chat(Map.of("session_id", "chat1", "query", "问题", "user_id", "other"), actor);
    var output = new ByteArrayOutputStream();
    stream.writeTo(output);
    assertEquals(events, output.toString(StandardCharsets.UTF_8));
    verify(repository).insertUserMessage(anyString(), eq("chat1"), eq("user"), eq("问题"), any());
    verify(repository)
        .insertAssistantMessage(anyString(), eq("chat1"), eq("assistant"), eq("你好"), any());
    verify(client).openChat(actor, "chat1", "问题");
  }

  @Test
  void partialStreamReportsInterruptionAndKeepsReceivedText() throws Exception {
    upstream("data: {\"content\":\"部分回答\"}\n\n");
    var output = new ByteArrayOutputStream();
    service.chat(Map.of("session_id", "chat1", "query", "问题"), actor).writeTo(output);
    assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: error\n"));
    verify(repository)
        .insertAssistantMessage(anyString(), eq("chat1"), eq("assistant"), eq("部分回答"), any());
  }

  @Test
  void deletedSessionDoesNotGetAssistantArchiveAfterStream() throws Exception {
    upstream("data: {\"content\":\"回答\"}\n\ndata: {\"done\":true}\n\n");
    when(repository.countActiveSessions("chat1")).thenReturn(0L);
    service
        .chat(Map.of("session_id", "chat1", "query", "问题"), actor)
        .writeTo(new ByteArrayOutputStream());
    verify(repository, never()).insertAssistantMessage(any(), any(), any(), any(), any());
  }

  @Test
  void foreignSessionFailsBeforeUpstreamOrDatabaseWrites() {
    when(trade.owned("chat_sessions", "other", "u1", false))
        .thenThrow(new ApiError(404, "NOT_FOUND", "不存在"));
    assertThrows(
        ApiError.class, () -> service.chat(Map.of("session_id", "other", "query", "问题"), actor));
    verifyNoInteractions(client, repository);
  }
}
