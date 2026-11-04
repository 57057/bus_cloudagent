package com.cloudagent.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudagent.business.entity.CloudInstance;

/** 历史 cloud_instances 的通用 Mapper，供旧版实例归属和列表查询使用。 不要把旧表实例与 P0 模拟供应商实例台账混为一套数据。 */
public interface CloudInstanceMapper extends BaseMapper<CloudInstance> {}
