package com.cloudagent.business.entity;

import com.baomidou.mybatisplus.annotation.*;

/**
 * 旧版订单表 cloud_orders 的最小实体映射，供历史 BusinessService 查询使用。 它不是 P0 新交易表 p0_orders
 * 的实体，不要在这里寻找新下单/库存/支付状态的实现。 部分历史查询通过 selectMaps 指定返回列，因此实体只声明查询条件需要的主键和用户列。
 */
@TableName("cloud_orders")
public class CloudOrder {
  /** 历史order_id列，供旧查询指定记录。 */
  @TableId(value = "order_id", type = IdType.INPUT)
  public String id;

  /** 历史订单归属编号，不能自动视为新平台注册账号。 */
  @TableField("user_id")
  public String userId;
}
