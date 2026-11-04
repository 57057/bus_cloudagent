package com.cloudagent.business.entity;

import com.baomidou.mybatisplus.annotation.*;

/**
 * 旧版实例表 cloud_instances 的最小实体映射，供历史查询与归属检查使用。 当前 P0 实例生命周期在 p0_instances、ResourceService、JobRunner
 * 中实现。 此类字段少不表示原表只有这些列；历史 selectMaps 查询可以显式选出其他列。
 */
@TableName("cloud_instances")
public class CloudInstance {
  /** 历史instance_id列，不是新模拟实例表的主键映射。 */
  @TableId(value = "instance_id", type = IdType.INPUT)
  public String id;

  /** 历史资源归属编号，查询旧监控前使用它检查归属。 */
  @TableField("user_id")
  public String userId;
}
