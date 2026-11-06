package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.cloudagent.business.common.*;
import com.cloudagent.business.dto.ApplicationRequests.*;
import com.cloudagent.business.integration.ApplicationSecretVault;
import com.cloudagent.business.repository.*;
import com.cloudagent.business.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 取消不是直接改成成功；先拒绝发布，再等待候选资源清理确认。 */
class ApplicationControlsTest {
  final ApplicationRepository r=mock(ApplicationRepository.class);
  final PoolRepository pool=mock(PoolRepository.class);
  final AuthService.Actor user=new AuthService.Actor("u","USER","s");
  final ApplicationService app=new ApplicationService(r,pool,new JsonCodec(new ObjectMapper()),mock(BusinessEvents.class),"unused","unused");
  final Map<String,Object> task=new HashMap<>(Map.of("id","t","project_id","p","host_id","h","generation",3,"attempt",1,"kind","DEPLOY","state","RUNNING","cancel_requested",false,"commit_started",false));
  ApplicationControlsTest(){
    when(r.deployment("t","u")).thenReturn(task);
    when(r.task("t")).thenReturn(task);
    when(r.project("p","u")).thenReturn(Map.of("id","p","allocation_id","a"));
    when(r.allocation("a","u")).thenReturn(Map.of("id","a","host_id","h","generation",3));
    var h=Map.<String,Object>of("id","h","generation",3,"capabilities","{\"execution_protocol\":2}");
    when(pool.host("h")).thenReturn(h);when(pool.runner(anyString())).thenReturn(h);
  }
  @Test void cancellationRequiresConfirmation(){
    assertEquals("CONFIRM_REQUIRED",assertThrows(ApiError.class,()->app.cancel(user,"t",new Cancel(false))).code);
    verify(r,never()).requestCancel(anyString());
  }
  @Test void queuedTaskIsCancelledWithoutExternalEffects(){
    task.put("state","QUEUED");app.cancel(user,"t",new Cancel(true));
    verify(r).requestCancel("t");verify(r).cancelled(eq("t"),anyString());verify(r,never()).publish(anyString(),anyString());
  }
  @Test void runningTaskWaitsForCleanupAcknowledgement(){
    app.cancel(user,"t",new Cancel(true));verify(r).requestCancel("t");verify(r,never()).cancelled(anyString(),anyString());
  }
  @Test void finalCutoverCannotBeReportedAsCancelled(){
    task.put("commit_started",true);
    assertEquals("PUBLISH_IN_PROGRESS",assertThrows(ApiError.class,()->app.cancel(user,"t",new Cancel(true))).code);
    verify(r,never()).requestCancel(anyString());
  }
  @Test void cancelledTaskCannotAcquirePublicationPermit(){
    task.put("cancel_requested",true);
    assertEquals("CANCEL_REQUESTED",assertThrows(ApiError.class,()->app.beginCommit("credential","t",new Checkpoint(1))).code);
    verify(r,never()).beginCommit(anyString());
  }
  @Test void cleanupEvidenceIsRequiredAndCancelledVersionNeverPublishes(){
    task.put("cancel_requested",true);
    assertEquals("CANCEL_NOT_CONFIRMED",assertThrows(ApiError.class,()->app.complete("credential","t",new Completion(1,false,Map.of("cancelled",true)))).code);
    app.complete("credential","t",new Completion(1,false,Map.of("cancelled",true,"candidate_cleanup_confirmed",true)));
    verify(r).cancelled(eq("t"),anyString());verify(r,never()).publish(anyString(),anyString());
  }
  @Test void successfulHealthCannotBypassCancelledPublicationGate(){
    task.put("cancel_requested",true);
    assertEquals("PUBLISH_NOT_AUTHORIZED",assertThrows(ApiError.class,()->app.complete("credential","t",new Completion(1,true,Map.of("health",true)))).code);
    verify(r,never()).publish(anyString(),anyString());
  }
  @Test void stopAndStartKeepCurrentRelease(){
    task.put("kind","STOP");app.complete("credential","t",new Completion(1,true,Map.of("health",false)));
    verify(r).runtime("p","STOPPED");
    task.put("kind","START");app.complete("credential","t",new Completion(1,true,Map.of("health",true)));
    verify(r).runtime("p","RUNNING");verify(r,never()).publish(anyString(),anyString());
  }
  @Test void oldGenerationCannotRequestCancellation(){
    task.put("generation",2);
    assertEquals("STALE_ALLOCATION",assertThrows(ApiError.class,()->app.cancel(user,"t",new Cancel(true))).code);
    verify(r,never()).requestCancel(anyString());
  }
  @Test void otherUsersCannotCancelOwnersTask(){
    when(r.deployment("t","other")).thenThrow(new ApiError(404,"NOT_FOUND","不存在"));
    assertThrows(ApiError.class,()->app.cancel(new AuthService.Actor("other","USER","s"),"t",new Cancel(true)));
    verify(r,never()).requestCancel(anyString());
  }
  @Test void editingWelcomePreservesEncryptedApplicationSecret(){
    var vault=mock(ApplicationSecretVault.class);
    org.springframework.test.util.ReflectionTestUtils.setField(app,"vault",vault);
    when(r.config("p")).thenReturn(List.of(Map.of("version",1,"variables","{}","secrets_cipher","encrypted","secret_names","[\"API_TOKEN\"]")));
    when(vault.decrypt("encrypted")).thenReturn(Map.of("API_TOKEN","synthetic-test-value"));
    when(vault.encrypt(anyMap())).thenReturn("new-encrypted");
    app.welcome(user,"p",new WelcomeMessage("new welcome"));
    verify(vault).encrypt(Map.of("API_TOKEN","synthetic-test-value"));
    verify(r).config(anyString(),eq("p"),eq(2L),eq("{\"APP_MESSAGE\":\"new welcome\"}"),eq("new-encrypted"),eq("[\"API_TOKEN\"]"));
  }
}
