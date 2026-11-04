package com.cloudagent.business.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;

/**
 * 当前 P0 用户实体，对应 p0_users 表；字段是列映射，而不是 HTTP 请求参数。 {@code @TableId} 指明主键由应用赋值；@TableField 把 Java
 * 驼峰字段映射到数据库下划线列名。 passwordHash 上的 @JsonIgnore 只阻止 JSON 输出，不妨碍 MyBatis-Plus 读写数据库。 此简化实体使用 public
 * 字段，实际项目也常使用 private 字段配合 getter/setter。
 */
@TableName("p0_users")
public class PlatformUser {
  /** 应用生成的用户主键，不是用户可以在表单中指定的身份。 */
  @TableId(type = IdType.INPUT)
  public String id;

  /** 唯一登录名，注册时统一转为小写。 */
  public String username;

  /** BCrypt哈希；只保存校验材料，不保存明文密码，也不向JSON返回。 */
  @JsonIgnore
  @TableField("password_hash")
  public String passwordHash;

  /** 页面显示昵称，与登录名和主键的用途不同。 */
  public String nickname;

  /** 指向受保护附件记录的编号，不是任意外部头像URL。 */
  @TableField("avatar_id")
  public String avatarId;

  /** 单一平台角色：USER、OPERATOR或ADMIN。 */
  public String role;

  /** 账号启用状态；认证每次都会检查，停用不等于删除已付款资源。 */
  public Boolean enabled;

  /** 是否同意提取长期偏好，默认false；它不是短期会话开关。 */
  @TableField("preferences_enabled")
  public Boolean preferencesEnabled;

  /** 注册时间；项目按UTC保存，前端按上海时区展示。 */
  @TableField("created_at")
  public Date createdAt;
}
