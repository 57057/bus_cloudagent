package com.cloudagent.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudagent.business.entity.PlatformUser;

/**
 * MyBatis-Plus 用户 Mapper；无需手写 selectById/insert 等常规 CRUD 的 SQL。 {@code BaseMapper<PlatformUser>}
 * 的泛型说明操作哪种实体，表名及列名来自实体上的注解。 它是接口，启动时由 @MapperScan/MyBatis 创建代理实现，不是由你手动 new 接口。
 */
public interface PlatformUserMapper extends BaseMapper<PlatformUser> {}
