package com.example.seckilldemo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckilldemo.entity.TSeckillOrder;
import com.example.seckilldemo.entity.TUser;
import com.example.seckilldemo.mapper.TSeckillOrderMapper;
import com.example.seckilldemo.service.ITSeckillOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Objects;

/**
 * 秒杀订单表 服务实现类
 *
 * @author LiChao
 * @since 2022-03-03
 */
@Service
@Primary
public class TSeckillOrderServiceImpl extends ServiceImpl<TSeckillOrderMapper, TSeckillOrder> implements ITSeckillOrderService {

    @Autowired
    private TSeckillOrderMapper tSeckillOrderMapper;
    @Resource
    private RedisTemplate redisTemplate;

    /**
     * 获取秒杀结果
     * @param tUser
     * @param goodsId
     * @return
     */
    @Override
    public Long getResult(TUser tUser, Long goodsId) {
        try {
            TSeckillOrder seckillOrder = tSeckillOrderMapper.selectOne(new QueryWrapper<TSeckillOrder>()
                    .eq("user_id", tUser.getId())
                    .eq("goods_id", goodsId));
            if (Objects.nonNull(seckillOrder)) {
                return seckillOrder.getId();
            } else {
                if (Boolean.TRUE.equals(redisTemplate.hasKey("isStockEmpty:" + goodsId))) {
                    return -1L;
                } else {
                    return 0L;
                }
            }
        } catch (Exception e) {
            // 添加错误处理机制
            e.printStackTrace();
            return 0L; // 或者抛出自定义异常，根据业务需求决定
        }
    }
}
