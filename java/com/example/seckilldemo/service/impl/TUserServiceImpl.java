package com.example.seckilldemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckilldemo.entity.TUser;
import com.example.seckilldemo.exception.GlobalException;
import com.example.seckilldemo.mapper.TUserMapper;
import com.example.seckilldemo.service.ITUserService;
import com.example.seckilldemo.utils.CookieUtil;
import com.example.seckilldemo.utils.MD5Util;
import com.example.seckilldemo.utils.UUIDUtil;
import com.example.seckilldemo.vo.LoginVo;
import com.example.seckilldemo.vo.RespBean;
import com.example.seckilldemo.vo.RespBeanEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.thymeleaf.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author LiChao
 * @since 2022-03-02
 */
@Service
@Primary

public class TUserServiceImpl extends ServiceImpl<TUserMapper, TUser> implements ITUserService {

    // 注入TUserMapper，用于数据库操作
    @Autowired
    private TUserMapper tUserMapper;

    // 注入RedisTemplate，用于操作Redis
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 用户登录方法
     * @param loginVo 包含用户登录信息的对象
     * @param request HttpServletRequest对象，用于获取请求信息
     * @param response HttpServletResponse对象，用于设置响应信息
     * @return 包含登录结果的响应对象
     */
    @Override
    public RespBean doLongin(LoginVo loginVo, HttpServletRequest request, HttpServletResponse response) {

        String mobile = loginVo.getMobile();
        String password = loginVo.getPassword();

        // 根据手机号码从数据库中查询用户信息
        TUser user = tUserMapper.selectById(mobile);
        if (user == null) {
            // 如果用户不存在，抛出全局异常
            throw new GlobalException(RespBeanEnum.LOGIN_ERROR);
        }
        // 判断密码是否正确
        if (!MD5Util.formPassToDBPass(password, user.getSalt()).equals(user.getPassword())) {
            // 如果密码不正确，抛出全局异常
            throw new GlobalException(RespBeanEnum.LOGIN_ERROR);
        }
        // 生成唯一的用户票据
        String userTicket = UUIDUtil.uuid();
        // 将用户信息存入Redis，键为 "user:" 加上用户票据
        redisTemplate.opsForValue().set("user:" + userTicket, user);

        // 将用户票据设置到Cookie中
        CookieUtil.setCookie(request, response, "userTicket", userTicket);
        // 返回登录成功的响应对象，包含用户票据
        return RespBean.success(userTicket);
    }

    /**
     * 根据用户票据获取用户信息
     * @param userTicket 用户票据
     * @param request HttpServletRequest对象，用于获取请求信息
     * @param response HttpServletResponse对象，用于设置响应信息
     * @return 用户对象，如果票据无效则返回null
     */
    @Override
    public TUser getUserByCookie(String userTicket, HttpServletRequest request, HttpServletResponse response) {
        if (StringUtils.isEmpty(userTicket)) {
            // 如果用户票据为空，返回null
            return null;
        }
        // 从Redis中获取用户信息
        TUser user = (TUser) redisTemplate.opsForValue().get("user:" + userTicket);
        if (user != null) {
            // 如果用户信息存在，更新Cookie中的用户票据
            CookieUtil.setCookie(request, response, "userTicket", userTicket);
        }else{
            log.error("用户信息不存在 更行用户信息失败");
        }
        return user;
    }

    /**
     * 更新用户密码
     * @param userTicket 用户票据
     * @param password 新密码
     * @param request HttpServletRequest对象，用于获取请求信息
     * @param response HttpServletResponse对象，用于设置响应信息
     * @return 包含更新结果的响应对象
     */
    @Override
    public RespBean updatePassword(String userTicket, String password, HttpServletRequest request, HttpServletResponse response) {
        // 根据用户票据获取用户信息
        TUser user = getUserByCookie(userTicket, request, response);
        if (user == null) {
            // 如果用户信息不存在，抛出全局异常
            throw new GlobalException(RespBeanEnum.MOBILE_NOT_EXIST);
        }
        // 将新密码进行MD5加密后更新到用户对象中
        user.setPassword(MD5Util.inputPassToDBPass(password, user.getSalt()));
        // 更新数据库中的用户信息
        int result = tUserMapper.updateById(user);
        if (1 == result) {
            // 如果更新成功，删除Redis中的用户信息
            redisTemplate.delete("user:" + userTicket);
            // 返回更新成功的响应对象
            return RespBean.success();
        }
        // 如果更新失败，返回更新失败的响应对象
        return RespBean.error(RespBeanEnum.PASSWORD_UPDATE_FAIL);
    }
}