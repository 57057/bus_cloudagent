package com.cloudagent.business.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

/** 共享 JSON 序列化组件，与数据库访问无关。 */
@Component
public class JsonCodec {
  private final ObjectMapper json;

  public JsonCodec(ObjectMapper json) {
    this.json = json;
  }

  /** 把Java对象序列化为JSON字符串，用于保存报价快照、任务参数等；不是写JSON文件。 */
  public String json(Object data) {
    try {
      return json.writeValueAsString(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 把JSON对象字符串反序列化为Map；当前工具只面向对象结构，不用于解析顶层数组。 */
  public Map<String, Object> parse(Object data) {
    try {
      return json.readValue(data.toString(), new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
