package com.cloudagent.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudagent.business.entity.PlatformProduct;

/**
 * MyBatis-Plus 产品 Mapper，继承产品实体的通用 CRUD。 CatalogService 调用接口方法，MyBatis-Plus 根据 PlatformProduct
 * 的映射生成常规 SQL。 复杂库存锁和价格版本事务没有被 BaseMapper 自动实现，需看 CatalogService 的具体 SQL。
 */
public interface PlatformProductMapper extends BaseMapper<PlatformProduct> {}
