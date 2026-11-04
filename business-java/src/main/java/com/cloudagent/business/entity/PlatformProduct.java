package com.cloudagent.business.entity;

import com.baomidou.mybatisplus.annotation.*;

/**
 * 当前 P0 产品实体，对应 p0_products 表，供 PlatformProductMapper 的 CRUD 使用。 产品描述的是 SPU，地域、CPU、价格与库存等可售配置在
 * p0_skus 中，不放在本实体里。 active 是上下架标记，下架并不删除历史订单快照。
 */
@TableName("p0_products")
public class PlatformProduct {
  /** 产品/SPU编号，由应用赋值。 */
  @TableId(type = IdType.INPUT)
  public String id;

  /** 产品展示名称，例如弹性云服务器ECS。 */
  public String name;

  /** 产品类型，当前支持ECS/GPU/RDS/DISK分类。 */
  public String category;

  /** 产品说明，不应把这里的文本当作服务端计价公式。 */
  public String description;

  /** 适用场景展示文本。 */
  public String scenario;

  /** 是否上架；停用不物理删除历史交易。 */
  public Boolean active;

  /** 产品图片路径，维护时按当前规则检查路径格式。 */
  @TableField("image_url")
  public String imageUrl;
}
