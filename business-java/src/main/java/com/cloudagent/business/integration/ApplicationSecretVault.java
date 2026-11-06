package com.cloudagent.business.integration;

import com.cloudagent.business.common.JsonCodec;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本地演示的AES-GCM密钥库。主密钥与数据库分离；生产应使用外部KMS，备份恢复须同时保管此密钥。 */
@Component
public class ApplicationSecretVault {
  private final Path keyFile;
  private final JsonCodec json;
  private final SecureRandom random = new SecureRandom();

  public ApplicationSecretVault(
      JsonCodec json,
      @Value("${platform.application-key-file:runtime/application-secret.key}") String keyFile) {
    this.json = json;
    this.keyFile = Path.of(keyFile);
  }

  private synchronized byte[] key() throws Exception {
    if (!Files.exists(keyFile)) {
      Files.createDirectories(keyFile.toAbsolutePath().getParent());
      byte[] key = new byte[32];
      random.nextBytes(key);
      try {
        Files.write(keyFile, key, StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException ignored) {
      }
      try {
        Files.setPosixFilePermissions(
            keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      }
    }
    byte[] key = Files.readAllBytes(keyFile);
    if (key.length != 32) throw new IllegalStateException("Invalid application encryption key");
    return key;
  }

  public String encrypt(Map<String, String> data) {
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
      byte[] encoded =
          cipher.doFinal(json.json(data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(iv)
          + "."
          + Base64.getEncoder().encodeToString(encoded);
    } catch (Exception e) {
      throw new IllegalStateException("Application secrets cannot be encrypted");
    }
  }

  public Map<String, Object> decrypt(String value) {
    try {
      if (!Files.exists(keyFile)) throw new IllegalStateException("Missing encryption key");
      String[] pieces = value.split("\\.");
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key(), "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(pieces[0])));
      return json.parse(
          new String(
              cipher.doFinal(Base64.getDecoder().decode(pieces[1])),
              java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Application secrets cannot be decrypted");
    }
  }
}
