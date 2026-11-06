package com.cloudagent.business.bootstrap;

import com.cloudagent.business.common.Values;
import com.cloudagent.business.repository.SqlExecutor;
import com.cloudagent.business.service.AuthService;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 应用初始化时执行的增量数据库迁移和显式演示环境初始化。 InitializingBean.afterPropertiesSet 在 Spring 创建并注入此对象后调用，不是每次 HTTP
 * 请求调用。 命名锁避免多个实例同时迁移，SHA-256 检查防止已经应用的版本被悄悄改写。 MySQL DDL 可能隐式提交，因此这个类不是“一个注解包住所有建表即可随时回滚”的方案。
 * 备份/恢复由部署脚本另行完成；旧订单只做历史导入与对账，不伪造成新账号的已付款订单。
 */
@Component
public class SchemaMigration implements InitializingBean {
  private final com.cloudagent.business.common.JsonCodec json;
  private final com.cloudagent.business.service.BusinessEvents events;
  private final SqlExecutor s;
  private final DataSource ds;
  private final boolean demo;
  private final String admin, password;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public SchemaMigration(
      SqlExecutor s,
      com.cloudagent.business.common.JsonCodec json,
      com.cloudagent.business.service.BusinessEvents events,
      DataSource ds,
      @Value("${platform.demo}") boolean demo,
      @Value("${platform.admin-user}") String admin,
      @Value("${platform.admin-password}") String password) {
    this.s = s;
    this.json = json;
    this.events = events;
    this.ds = ds;
    this.demo = demo;
    this.admin = admin;
    this.password = password;
  }

  /** 依次验证/应用V001、V002，必要时导入旧订单并核对数量/金额；随后按部署参数初始化管理员和显式演示商品。 */
  public void afterPropertiesSet() throws Exception {
    // MySQL命名锁属于这条数据库连接，因此锁的获取和释放必须用同一个c。try-with-resources负责最终关闭连接。
    try (var c = ds.getConnection()) {
      try (var st = c.createStatement();
          var lock = st.executeQuery("SELECT GET_LOCK('cloud_platform_schema',30)")) {
        if (!lock.next() || lock.getInt(1) != 1)
          throw new IllegalStateException("Schema migration locked");
      }
      try {
        s.run(
            "CREATE TABLE IF NOT EXISTS p0_schema_history(version INT PRIMARY KEY, checksum"
                + " CHAR(64) NOT NULL, applied_at DATETIME(3) NOT NULL)");
        var resource = new ClassPathResource("db/V001__platform.sql");
        String checksum =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(resource.getContentAsByteArray()));
        var applied = s.list("SELECT checksum FROM p0_schema_history WHERE version=1");
        if (applied.isEmpty()) {
          ScriptUtils.executeSqlScript(c, resource);
          if (s.count(
                  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                      + " table_name='cloud_orders'")
              > 0) {
            s.run(
                "INSERT IGNORE INTO"
                    + " p0_legacy_orders(id,legacy_user_id,product_name,amount_cents,legacy_status)"
                    + " SELECT order_id,user_id,product_name,CAST(ROUND(amount*100) AS"
                    + " SIGNED),status FROM cloud_orders");
            if (s.count("SELECT COUNT(*) FROM cloud_orders")
                    != s.count("SELECT COUNT(*) FROM p0_legacy_orders")
                || s.count("SELECT COALESCE(SUM(ROUND(amount*100)),0) FROM cloud_orders")
                    != s.count("SELECT COALESCE(SUM(amount_cents),0) FROM p0_legacy_orders"))
              throw new IllegalStateException("Historical migration reconciliation failed");
          }
          s.run("INSERT INTO p0_schema_history VALUES(1,?,?)", checksum, Values.now());
        } else if (!checksum.equals(applied.get(0).get("checksum")))
          throw new IllegalStateException("Applied migration checksum changed");
        var second = new ClassPathResource("db/V002__notification_templates.sql");
        String secondChecksum =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(second.getContentAsByteArray()));
        var secondApplied = s.list("SELECT checksum FROM p0_schema_history WHERE version=2");
        if (secondApplied.isEmpty()) {
          ScriptUtils.executeSqlScript(c, second);
          s.run("INSERT INTO p0_schema_history VALUES(2,?,?)", secondChecksum, Values.now());
        } else if (!secondChecksum.equals(secondApplied.get(0).get("checksum")))
          throw new IllegalStateException("Applied migration checksum changed: 2");
        // 新增版本只追加迁移文件，已部署 V001/V002 的校验和保持不变。
        var third = new ClassPathResource("db/V003__p1.sql");
        String thirdChecksum =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(third.getContentAsByteArray()));
        var thirdApplied = s.list("SELECT checksum FROM p0_schema_history WHERE version=3");
        if (thirdApplied.isEmpty()) {
          ScriptUtils.executeSqlScript(c, third);
          s.run("INSERT INTO p0_schema_history VALUES(3,?,?)", thirdChecksum, Values.now());
        } else if (!thirdChecksum.equals(thirdApplied.get(0).get("checksum")))
          throw new IllegalStateException("Applied migration checksum changed: 3");
        var fourth = new ClassPathResource("db/V004__application_platform.sql");
        String checksum4 =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(fourth.getContentAsByteArray()));
        var applied4 = s.list("SELECT checksum FROM p0_schema_history WHERE version=4");
        if (applied4.isEmpty()) {
          ScriptUtils.executeSqlScript(c, fourth);
          s.run("INSERT INTO p0_schema_history VALUES(4,?,?)", checksum4, Values.now());
        } else if (!checksum4.equals(applied4.get(0).get("checksum")))
          throw new IllegalStateException("Applied migration checksum changed: 4");
        var fifth = new ClassPathResource("db/V005__application_configs.sql");
        String checksum5 =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(fifth.getContentAsByteArray()));
        var applied5 = s.list("SELECT checksum FROM p0_schema_history WHERE version=5");
        if (applied5.isEmpty()) {
          ScriptUtils.executeSqlScript(c, fifth);
          s.run("INSERT INTO p0_schema_history VALUES(5,?,?)", checksum5, Values.now());
        } else if (!checksum5.equals(applied5.get(0).get("checksum")))
          throw new IllegalStateException("Applied migration checksum changed: 5");
        var sixth = new ClassPathResource("db/V006__pool_finance.sql");
        String checksum6 =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256").digest(sixth.getContentAsByteArray()));
        var applied6 = s.list("SELECT checksum FROM p0_schema_history WHERE version=6");
        if (applied6.isEmpty()) {
          ScriptUtils.executeSqlScript(c, sixth);
          s.run("INSERT INTO p0_schema_history VALUES(6,?,?)", checksum6, Values.now());
        } else if (!checksum6.equals(applied6.get(0).get("checksum")))
          throw new IllegalStateException("Applied migration checksum changed: 6");
        var seventh = new ClassPathResource("db/V007__application_controls.sql");
        String checksum7 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seventh.getContentAsByteArray()));
        var applied7 = s.list("SELECT checksum FROM p0_schema_history WHERE version=7");
        if (applied7.isEmpty()) {
          ScriptUtils.executeSqlScript(c, seventh);
          s.run("INSERT INTO p0_schema_history VALUES(7,?,?)", checksum7, Values.now());
        } else if (!checksum7.equals(applied7.get(0).get("checksum")))
          throw new IllegalStateException("Applied migration checksum changed: 7");
      } finally {
        try (var st = c.createStatement()) {
          st.execute("SELECT RELEASE_LOCK('cloud_platform_schema')");
        }
      }
    }
    // 管理员账号只在显式部署配置存在且数据库尚无同名用户时初始化；以后启动不会用旧初始化密码覆盖已修改的密码。
    if (!admin.isBlank()
        && !password.isBlank()
        && s.count("SELECT COUNT(*) FROM p0_users WHERE username=?", admin) == 0) {
      AuthService.validatePassword(password);
      s.run(
          "INSERT INTO p0_users(id,username,password_hash,nickname,role,enabled,created_at)"
              + " VALUES(?,?,?,?,?,TRUE,?)",
          Values.id(),
          admin,
          new BCryptPasswordEncoder().encode(password),
          "平台管理员",
          "ADMIN",
          Values.now());
      events.audit("DEPLOYMENT", "ADMIN_BOOTSTRAP", admin, "部署初始化管理员，未记录密码");
    }
    if (demo && s.count("SELECT COUNT(*) FROM p0_products") == 0) {
      s.run(
          "INSERT INTO p0_products VALUES('ecs','弹性云服务器"
              + " ECS','ECS','按月或按年租用，模拟环境完整演示购买与资源生命周期。','网站部署 · 开发测试 · 轻量服务',TRUE,'')");
      s.run(
          "INSERT INTO p0_products VALUES('gpu','GPU 计算','GPU','暂未配置供应能力，首版仅展示。','模型推理 ·"
              + " 图形计算',TRUE,'')");
      s.run(
          "INSERT INTO p0_products VALUES('rds','关系型数据库"
              + " RDS','RDS','暂未配置开通适配器，首版仅展示。','托管数据库',TRUE,'')");
      for (int i = 1; i <= 3; i++) {
        String id = "ecs-" + i;
        s.run(
            "INSERT INTO"
                + " p0_skus(id,product_id,name,region,zone,cpu,memory_gb,base_cents,disk_cents,bandwidth_cents,version,active,capacity)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,1,TRUE,?)",
            id,
            "ecs",
            new String[] {"轻量入门", "通用计算", "高性能计算"}[i - 1],
            i == 3 ? "cn-beijing" : "cn-hangzhou",
            i == 3 ? "cn-beijing-a" : "cn-hangzhou-a",
            i == 1 ? 2 : i == 2 ? 4 : 8,
            i == 1 ? 4 : i == 2 ? 8 : 16,
            i == 1 ? 3900 : i == 2 ? 7900 : 15900,
            5,
            300,
            20);
        var sku = s.one("SELECT * FROM p0_skus WHERE id=?", id);
        s.run(
            "INSERT INTO p0_price_versions VALUES(?,?,?,?,?)",
            Values.id(),
            id,
            1,
            json.json(sku),
            Values.now());
      }
    }
    if (demo) seedP1Demonstrations();
  }

  /** 更多类型使用同一套持久化模拟履约，不宣称生成实际GPU、数据库引擎或可挂载云盘。只初始化固定演示ID，不覆盖运营维护的SKU。 */
  private void seedP1Demonstrations() {
    s.run(
        "INSERT IGNORE INTO p0_products"
            + " VALUES('disk','独立云盘（模拟）','DISK','本地模拟云盘租用与生命周期，不创建可挂载的真实磁盘。','存储生命周期演示',TRUE,'')");
    s.run(
        "UPDATE p0_products SET description='可选本地模拟规格；不分配实际GPU或创建可运行的GPU主机。' WHERE id='gpu' AND"
            + " description='暂未配置供应能力，首版仅展示。'");
    s.run(
        "UPDATE p0_products SET description='本地模拟数据库资源生命周期；不创建真实数据库引擎。' WHERE id='rds' AND"
            + " description='暂未配置开通适配器，首版仅展示。'");
    for (String product : java.util.List.of("gpu", "rds", "disk")) {
      String sku = "p1-demo-" + product;
      s.run(
          "INSERT IGNORE INTO"
              + " p0_skus(id,product_id,name,region,zone,cpu,memory_gb,base_cents,disk_cents,bandwidth_cents,version,active,capacity)"
              + " VALUES(?,?,?,'cn-hangzhou','cn-hangzhou-a',4,16,9900,1,1,1,TRUE,10)",
          sku,
          product,
          "模拟 " + product.toUpperCase(java.util.Locale.ROOT) + " 生命周期");
      var row = s.one("SELECT * FROM p0_skus WHERE id=?", sku);
      s.run(
          "INSERT IGNORE INTO p0_price_versions(id,sku_id,version,snapshot,created_at)"
              + " VALUES(?,?,1,?,?)",
          Values.id(),
          sku,
          json.json(row),
          Values.now());
    }
  }
}
