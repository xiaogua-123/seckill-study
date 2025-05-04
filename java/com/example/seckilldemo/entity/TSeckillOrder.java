package com.example.seckilldemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀订单表
 *
 * @author LiChao
 * @since 2022-03-03
 */
@TableName("t_seckill_order")
@ApiModel(value = "秒杀订单表", description = "秒杀订单表")
@Data
public class TSeckillOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 秒杀订单ID **/
    @ApiModelProperty("秒杀订单ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID **/
    @ApiModelProperty("用户ID")
    private Long userId;

    /** 订单ID **/
    @ApiModelProperty("订单ID")
    private Long orderId;

    /** 商品ID **/
    @ApiModelProperty("商品ID")
    private Long goodsId;
}