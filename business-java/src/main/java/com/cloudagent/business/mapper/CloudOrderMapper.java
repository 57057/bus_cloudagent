package com.cloudagent.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudagent.business.entity.CloudOrder;

/** 历史 cloud_orders 的通用 Mapper，只用于旧 BusinessService 的订单查询。 当前 P0 的订单创建与状态流转请阅读 TradeService。 */
public interface CloudOrderMapper extends BaseMapper<CloudOrder> {}
