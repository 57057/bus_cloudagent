package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.integration.AlipaySandboxClient;
import com.cloudagent.business.integration.AliyunClient;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ProviderDiagnosticsTest {
  @Test
  void rejectsApplicationPublicKeyAndAcceptsSeparateRsaKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var application = generator.generateKeyPair();
    var platform = generator.generateKeyPair();
    String privateKey = Base64.getEncoder().encodeToString(application.getPrivate().getEncoded());
    String ownPublic = Base64.getEncoder().encodeToString(application.getPublic().getEncoded());
    String platformPublic = Base64.getEncoder().encodeToString(platform.getPublic().getEncoded());
    assertEquals(
        "ALIPAY_WRONG_PUBLIC_KEY",
        assertThrows(ApiError.class, () -> AlipaySandboxClient.validateKeys(privateKey, ownPublic))
            .code);
    assertDoesNotThrow(() -> AlipaySandboxClient.validateKeys(privateKey, platformPublic));
  }

  @Test
  void malformedKeyHasSanitizedError() {
    var error =
        assertThrows(
            ApiError.class,
            () -> AlipaySandboxClient.validateKeys("sensitive-invalid-value", "bad"));
    assertEquals("ALIPAY_KEY_INVALID", error.code);
    assertFalse(error.getMessage().contains("sensitive"));
  }

  @Test
  void separatesBalancePermissionsAndResourceMappingWithoutEchoingRemoteText() {
    assertEquals(
        "ALIYUN_ACCOUNT_BALANCE",
        AliyunClient.requestFailure("InvalidAccountStatus.NotEnoughBalance").code);
    assertEquals("ALIYUN_PERMISSION_DENIED", AliyunClient.requestFailure("Forbidden.RAM").code);
    assertEquals(
        "ALIYUN_RESOURCE_CONFIG", AliyunClient.requestFailure("InvalidZoneId.NotFound").code);
    assertEquals("ALIYUN_REQUEST_FAILED", AliyunClient.requestFailure(null).code);
    assertFalse(AliyunClient.requestFailure("secret-remote-value").getMessage().contains("secret"));
  }
}
