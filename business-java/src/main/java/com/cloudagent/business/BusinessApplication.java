package com.cloudagent.business;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java 服务的启动入口。运行 main 后，Spring Boot 创建应用容器并启动内嵌 Tomcat。 {@code @SpringBootApplication} 从本包向下扫描
 * Controller、Service、Component 等对象； {@code @MapperScan} 注册 MyBatis Mapper 接口；@EnableScheduling 启用
 * JobRunner 中的定时任务。 因此业务服务、接口和后台任务都在这一个 Java 进程中，Python Agent 是另一个独立进程。
 */
@SpringBootApplication
@MapperScan("com.cloudagent.business.mapper")
@org.springframework.scheduling.annotation.EnableScheduling
public class BusinessApplication {
  /** 启动 Spring 应用容器。args 是命令行参数；组件扫描、依赖注入和 HTTP 服务启动由框架完成。 */
  public static void main(String[] args) {
    SpringApplication.run(BusinessApplication.class, args);
  }
}
