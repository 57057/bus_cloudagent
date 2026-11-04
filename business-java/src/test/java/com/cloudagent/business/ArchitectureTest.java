package com.cloudagent.business;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 构建时执行的分层约束，不需要启动数据库；越层导入、内联 SQL 或事务放错层会使构建失败。 */
class ArchitectureTest {
  private static final Path ROOT = Path.of("src/main/java/com/cloudagent/business");
  // 字符串先匹配，避免把 URL 中的 // 当注释；保留字面量供 SQL 检查使用。
  private static final Pattern TOKENS =
      Pattern.compile(
          "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/");

  private String code(Path path) throws Exception {
    return stripComments(Files.readString(path));
  }

  private String stripComments(String source) {
    return TOKENS
        .matcher(source)
        .replaceAll(
            match -> {
              String token = match.group();
              return token.startsWith("//") || token.startsWith("/*")
                  ? " "
                  : java.util.regex.Matcher.quoteReplacement(token);
            });
  }

  @Test
  void scannerKeepsStringLiteralsAndCodeAfterComments() {
    String source =
        "// first comment\n"
            + "String url = \"http://localhost/\"; /* SQL comment */\n"
            + "import example.Repository;";
    String stripped = stripComments(source);
    assertFalse(stripped.contains("first comment"));
    assertFalse(stripped.contains("SQL comment"));
    assertTrue(stripped.contains("http://localhost/"));
    assertTrue(stripped.contains("import example.Repository;"));
  }

  @Test
  void productionSourcesRespectLayerBoundaries() throws Exception {
    List<String> violations = new ArrayList<>();
    try (var files = Files.walk(ROOT)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (ROOT.relativize(file).getNameCount() == 1) {
          assertEquals("BusinessApplication.java", file.getFileName().toString(), "根包仅保留启动入口");
          continue;
        }
        String layer = ROOT.relativize(file).getName(0).toString();
        String source = code(file);
        assertTrue(
            Set.of(
                    "controller",
                    "service",
                    "repository",
                    "mapper",
                    "entity",
                    "dto",
                    "integration",
                    "job",
                    "web",
                    "common",
                    "bootstrap")
                .contains(layer),
            file + " 必须放入明确的职责包");
        assertTrue(
            source.contains("package com.cloudagent.business." + layer + ";"), file + " 包名与目录不匹配");
        if (Set.of("controller", "service").contains(layer)) {
          reject(
              violations,
              file,
              source,
              "org\\.springframework\\.jdbc|java\\.sql\\.(Connection|Statement|PreparedStatement)|javax\\.sql\\.DataSource|\\bSqlExecutor\\b",
              "接口/业务层不能直接使用 JDBC");
          reject(
              violations,
              file,
              source,
              "\"\\s*(?i:SELECT\\s|INSERT\\s|UPDATE\\s|DELETE\\s|CREATE\\s|ALTER\\s)",
              "SQL 必须位于 Repository/Mapper");
        }
        if (layer.equals("controller")) {
          reject(
              violations,
              file,
              source,
              "com\\.cloudagent\\.business\\.(repository|mapper|integration)\\.",
              "Controller 只能通过 Service 访问业务和外部系统");
          reject(
              violations,
              file,
              source,
              "@(?:[\\w.]+\\.)?Transactional|TransactionTemplate",
              "事务必须由业务层管理");
          reject(
              violations,
              file,
              source,
              "(?:private|public|protected)\\s+(?:final\\s+)?\\w+Controller\\s+\\w+",
              "Controller 不得依赖另一个 Controller");
        }
        if (layer.equals("service")) {
          reject(
              violations,
              file,
              source,
              "com\\.cloudagent\\.business\\.(controller|web|mapper)\\.|jakarta\\.servlet|org\\.springframework\\.web|com\\.baomidou",
              "Service 不依赖 HTTP 层或 ORM 细节");
        }
        if (Set.of("repository", "mapper", "entity", "dto", "common").contains(layer)) {
          reject(
              violations,
              file,
              source,
              "com\\.cloudagent\\.business\\.(controller|service|web|integration)\\.",
              "底层不能反向依赖业务或表现层");
        }
        if (Set.of("entity", "dto", "common").contains(layer)) {
          reject(
              violations,
              file,
              source,
              "com\\.cloudagent\\.business\\.(repository|mapper)\\.|org\\.springframework\\.jdbc|javax\\.sql\\.DataSource",
              "实体、DTO 和公共值组件不得访问数据库");
        }
        if (Set.of("repository", "mapper").contains(layer)) {
          reject(
              violations,
              file,
              source,
              "jakarta\\.servlet|org\\.springframework\\.web|@(?:[\\w.]+\\.)?Transactional|TransactionTemplate",
              "数据访问层不依赖 HTTP，也不负责用例事务边界");
        }
        if (source.contains("@RestController") && !source.contains("@RestControllerAdvice"))
          assertEquals("controller", layer, file + " REST 入口应放在 controller 包");
      }
    }
    assertTrue(violations.isEmpty(), String.join("\n", violations));
  }

  private void reject(
      List<String> violations, Path file, String code, String regex, String reason) {
    if (Pattern.compile(regex).matcher(code).find()) violations.add(file + ": " + reason);
  }
}
