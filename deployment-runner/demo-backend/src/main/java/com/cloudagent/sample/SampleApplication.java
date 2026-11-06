package com.cloudagent.sample;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

/** 独立示例应用，不读取平台数据库或平台凭据。 */
@SpringBootApplication
@RestController
public class SampleApplication {
  public static void main(String[] args) {
    SpringApplication.run(SampleApplication.class, args);
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("healthy", true, "application", "spring-react-demo");
  }

  @GetMapping("/api/hello")
  public Map<String, Object> hello() {
    return Map.of(
        "message",
        System.getenv().getOrDefault("APP_MESSAGE", "Spring Boot + React 已部署到平台分配的实例"),
        "version",
        "1.0.0");
  }

  @GetMapping("/api/protected")
  public org.springframework.http.ResponseEntity<?> protectedApi(
      @RequestHeader(value = "X-App-Token", defaultValue = "") String provided) {
    String expected = System.getenv().getOrDefault("API_TOKEN", "");
    if (expected.isBlank()
        || !java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            provided.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      return org.springframework.http.ResponseEntity.status(401)
          .body(Map.of("message", "Application credential required"));
    return org.springframework.http.ResponseEntity.ok(Map.of("authenticated", true));
  }
}
