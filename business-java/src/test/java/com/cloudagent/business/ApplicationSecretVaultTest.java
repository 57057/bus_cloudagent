package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.integration.ApplicationSecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationSecretVaultTest {
  @Test
  void authenticatedEncryptionDoesNotExposeSecretAndUsesFreshNonce() throws Exception {
    Path directory =
        java.nio.file.Files.createDirectories(
            Path.of("target/test-secrets", java.util.UUID.randomUUID().toString()));
    try {
      var vault =
          new ApplicationSecretVault(
              new JsonCodec(new ObjectMapper()), directory.resolve("key").toString());
      String first = vault.encrypt(Map.of("API_TOKEN", "private-test-value"));
      String second = vault.encrypt(Map.of("API_TOKEN", "private-test-value"));
      assertNotEquals(first, second);
      assertFalse(first.contains("private-test-value"));
      assertEquals("private-test-value", vault.decrypt(first).get("API_TOKEN"));
      String corrupted = first.substring(0, first.length() - 4) + "AAAA";
      assertThrows(IllegalStateException.class, () -> vault.decrypt(corrupted));
    } finally {
      java.nio.file.Files.deleteIfExists(directory.resolve("key"));
      java.nio.file.Files.deleteIfExists(directory);
    }
  }
}
