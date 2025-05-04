package com.example.seckilldemo.controller;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.seckilldemo.config.AccessLimit;
import com.example.seckilldemo.entity.TOrder;
import com.example.seckilldemo.entity.TSeckillOrder;
import com.example.seckilldemo.entity.TUser;
import com.example.seckilldemo.exception.GlobalException;
import com.example.seckilldemo.rabbitmq.MQSender;
import com.example.seckilldemo.service.ITGoodsService;
import com.example.seckilldemo.service.ITOrderService;
import com.example.seckilldemo.service.ITSeckillOrderService;
import com.example.seckilldemo.utils.JsonUtil;
import com.example.seckilldemo.vo.GoodsVo;
import com.example.seckilldemo.vo.RespBean;
import com.example.seckilldemo.vo.RespBeanEnum;
import com.example.seckilldemo.vo.SeckillMessage;
import com.wf.captcha.ArithmeticCaptcha;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀
 *
 * @author: LC
 * @date 2022/3/4 11:34 上午
 * @ClassName: SeKillController
 */
@Slf4j
@Controller
@RequestMapping("/seckill")
@Api(value = "秒杀", tags = "秒杀")
public class SeKillController implements InitializingBean {

    @Autowired
    private ITGoodsService itGoodsService;
    @Autowired
    private ITSeckillOrderService itSeckillOrderService;
    @Autowired
    private ITOrderService orderService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private MQSender mqSender;
    @Autowired
    private RedisScript<Long> redisScript;

    private Map<Long, Boolean> EmptyStockMap = new HashMap<>();

    @GetMapping(value = "/captcha")
    public void verifyCode(TUser tUser, @RequestParam("goodsId") Long goodsId, HttpServletResponse response) {
        if (tUser == null || goodsId < 0) {
            log.error("用户未登录，重定向到登录页面");
            try {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "请求非法");
            } catch (IOException e) {
                log.error("发送错误响应失败", e);
            }
            return;
        }

        // 设置请求头为输出图片的类型
        response.setContentType("image/jpg");
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        // 生成验证码
        ArithmeticCaptcha captcha = new ArithmeticCaptcha(130, 32, 3);

        try {
            redisTemplate.opsForValue().set("captcha:" + tUser.getId() + ":" + goodsId, captcha.text(), 2, TimeUnit.MINUTES);
            captcha.out(response.getOutputStream());
            log.info("验证码生成成功");
        } catch (IOException e) {
            log.error("验证码生成失败", e);
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "验证码生成失败");
            } catch (IOException ex) {
                log.error("发送错误响应失败", ex);
            }
        }
    }

    @ApiOperation("获取秒杀地址")
    @AccessLimit(second = 5, maxCount = 5, needLogin = true)
    @GetMapping(value = "/path")
    @ResponseBody
    public RespBean getPath(TUser user, Long goodsId, String captcha) {
        if (user == null) {
            log.info("用户未登录，重定向到登录页面");
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        if(goodsId == null || goodsId < 0){
            return RespBean.error(RespBeanEnum.REQUEST_ILLEGAL);
        }

        boolean check = orderService.checkCaptcha(user, goodsId, captcha);
        if (!check) {
            log.info("验证码错误，用户ID: {}, 商品ID: {}", user.getId(), goodsId);
            return RespBean.error(RespBeanEnum.ERROR_CAPTCHA);
        }
        String str = orderService.createPath(user, goodsId);
        log.info("获取秒杀地址成功，用户ID: {}, 商品ID: {}, 秒杀地址: {}");
        return RespBean.success(str);
    }

    @ApiOperation("获取秒杀结果")
    @GetMapping("getResult")
    @ResponseBody
    public RespBean getResult(TUser tUser, Long goodsId) {
        if (tUser == null) {
            log.info("用户未登录，重定向到登录页面");
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        Long orderId = itSeckillOrderService.getResult(tUser, goodsId);
        return RespBean.success(orderId);
    }

    @ApiOperation("最终实现--秒杀")
    @RequestMapping(value = "/{path}/doSeckill", method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSecKill(@PathVariable String path, TUser user, Long goodsId) {
        if (user == null) {
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        boolean check = orderService.checkPath(user, goodsId, path);
        if (!check) {
            return RespBean.error(RespBeanEnum.REQUEST_ILLEGAL);
        }

        // 判断是否重复抢购
        TSeckillOrder tSeckillOrder = (TSeckillOrder) valueOperations.get("order:" + user.getId() + ":" + goodsId);
        if (tSeckillOrder != null) {
            return RespBean.error(RespBeanEnum.REPEATE_ERROR);
        }
        // 内存标记，减少Redis的访问
        if (Boolean.TRUE.equals(EmptyStockMap.get(goodsId))) {
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }
        // 预减库存
        Long stock = redisTemplate.execute(redisScript, Collections.singletonList("seckillGoods:" + goodsId), Collections.EMPTY_LIST);
        if (stock < 0) {
            EmptyStockMap.put(goodsId, true);
            valueOperations.increment("seckillGoods:" + goodsId);
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }

        SeckillMessage seckillMessage = new SeckillMessage(user, goodsId);
        mqSender.sendSeckillMessage(JsonUtil.object2JsonStr(seckillMessage));
        return RespBean.success(0);
    }
    @ApiOperation("基础--秒杀功能")
    @RequestMapping(value = "/doSeckill", method = RequestMethod.POST)
    public String doSeckill(Model model, TUser user, Long goodsId) {
        try {
            // 检查用户是否登录
            if (user == null) {
                log.info("用户未登录，重定向到登录页面");
                return "login";
            }
            model.addAttribute("user", user);

            // 查询商品信息
            GoodsVo goods = itGoodsService.findGoodsVobyGoodsId(goodsId);
            if (goods == null) {
                log.warn("商品未找到，商品ID: {}", goodsId);
                model.addAttribute("errmsg", "商品未找到");
                return "seckillFail";
            }

            // 判断库存
            if (goods.getStockCount() < 1) {
                log.info("商品库存不足，商品ID: {}", goodsId);
                model.addAttribute("errmsg", RespBeanEnum.EMPTY_STOCK.getMessage());
                return "seckillFail";
            }

            // 判断是否重复抢购
            ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
            TSeckillOrder tSeckillOrder = (TSeckillOrder) valueOperations.get("order:" + user.getId() + ":" + goodsId);
            if (tSeckillOrder != null) {
                System.out.println(tSeckillOrder);
                log.info("用户重复抢购，用户ID: {}, 商品ID: {}", user.getId(), goodsId);
                model.addAttribute("errmsg", RespBeanEnum.REPEATE_ERROR.getMessage());
                return "seckillFail";
            }

            // 执行秒杀操作
            TOrder order = orderService.secKill(user, goods);

            model.addAttribute("order", order);
            model.addAttribute("goods", goods);

            log.info("用户秒杀成功，用户ID: {}, 商品ID: {}, 订单ID: {}", user.getId(), goodsId, order.getId());
            return "orderDetail";
        } catch (Exception e) {
            log.error("秒杀过程中出现异常", e);
            model.addAttribute("errmsg", "系统繁忙，请稍后再试");
            return "seckillFail";
        }
    }

    @ApiOperation("mq--秒杀功能")
    @RequestMapping(value = "/doSeckill_mq", method = RequestMethod.POST)
    @ResponseBody
    public RespBean doSecKill2(TUser user, Long goodsId, String captcha) {
        if (user == null) {
            return RespBean.error(RespBeanEnum.SESSION_ERROR);
        }
        boolean check = orderService.checkCaptcha(user, goodsId, captcha);
        if (!check) {
            return RespBean.error(RespBeanEnum.ERROR_CAPTCHA);
        }

        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        Long stock = valueOperations.decrement("seckillGoods:" + goodsId);
        if (stock < 0) {
            log.info("商品库存不足，商品ID: {}", goodsId);
            valueOperations.increment("seckillGoods:" + goodsId);
            return RespBean.error(RespBeanEnum.EMPTY_STOCK);
        }

        // 使用redis判断是否重复抢购
        TSeckillOrder tSeckillOrder = (TSeckillOrder) valueOperations.get("order:" + user.getId() + ":" + goodsId);
        if (tSeckillOrder != null) {
            return RespBean.error(RespBeanEnum.REPEATE_ERROR);
        }

        SeckillMessage seckillMessage = new SeckillMessage(user, goodsId);
        mqSender.sendSeckillMessage(JSON.toJSONString(seckillMessage));

        return RespBean.success();
    }
    @Override
    public void afterPropertiesSet() throws Exception {
        List<GoodsVo> list = itGoodsService.findGoodsVo();
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        list.forEach(goodsVo -> {
            valueOperations.set("seckillGoods:" + goodsVo.getId(), goodsVo.getStockCount());
            EmptyStockMap.put(goodsVo.getId(), false);
        });
    }
}