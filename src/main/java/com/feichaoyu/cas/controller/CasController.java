package com.feichaoyu.cas.controller;

import com.feichaoyu.cas.controller.vo.UserVO;
import com.feichaoyu.cas.dataobject.UserDO;
import com.feichaoyu.cas.util.AjaxResult;
import com.feichaoyu.cas.util.JsonUtils;
import com.feichaoyu.cas.util.RedisOperator;
import org.apache.catalina.User;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * @author feichaoyu
 */
@Controller
@CrossOrigin(origins = {"*"}, allowCredentials = "true")
public class CasController {

    @Autowired
    private RedisOperator redisOperator;

    private static final String COOKIE_USER_TICKET = "cookie_user_ticket";
    private static final String REDIS_USER_TICKET = "redis_user_ticket";
    private static final String REDIS_TMP_TICKET = "redis_tmp_ticket";
    private static final String REDIS_USER_INFO = "redis_user_info";


    @GetMapping("login")
    public void login(String returnUrl, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 获取全局门票，如果cookie中能够获取到，证明用户登录过
        String userTicket = getCookie(request, COOKIE_USER_TICKET);

        // 验证全局门票是否合法
        boolean isValid = verifyUserTicket(userTicket);
        if (isValid) {
            // 全局门票合法，那么再签发一个临时门票
            String tmpTicket = createTmpTicket();
            response.sendRedirect(returnUrl + "?tmpTicket=" + tmpTicket);
        } else {
            // TODO 前后端可以对returnUrl进行编解码传输
            response.sendRedirect("http://www.cas.com?returnUrl=" + returnUrl);
        }
    }

    /**
     * 验证全局门票是否合法
     *
     * @return
     */
    private boolean verifyUserTicket(String userTicket) {
        // 1.验证全局门票是否存在
        if (StringUtils.isBlank(userTicket)) {
            return false;
        }

        // 2.验证全局门票绑定的用户会话信息是否存在
        String userId = redisOperator.get(REDIS_USER_TICKET + ":" + userTicket);
        if (StringUtils.isBlank(userId)) {
            return false;
        }
        String userVO = redisOperator.get(REDIS_USER_INFO + ":" + userId);
        if (StringUtils.isBlank(userVO)) {
            return false;
        }
        return true;
    }

    @PostMapping("login")
    @ResponseBody
    public AjaxResult doLogin(String username,
                              String password,
                              String returnUrl,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        // 判断用户名密码不为空
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            return AjaxResult.error("用户名或密码不能为空");
        }

        // 1.模拟登录
        UserDO userDO1 = new UserDO().setId("1").setUsername("admin1").setPassword("admin1");
        UserDO userDO2 = new UserDO().setId("2").setUsername("admin2").setPassword("admin2");

//        if (!userDO.getUsername().equals(username) || !userDO.getPassword().equals(password)) {
//            return AjaxResult.error("用户名或密码不正确");
//        }

        UserDO userDO = new UserDO();
        if ("admin1".equals(username)) {
            userDO = userDO1;
        }else if ("admin2".equals(username)) {
            userDO = userDO2;
        }
        // 2.保存用户会话信息到redis中
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(userDO, userVO);
        redisOperator.set(REDIS_USER_INFO + ":" + userDO.getId(), JsonUtils.objectToJson(userVO));

        // 3.生成全局门票，代表用户在CAS端登录过
        String userTicket = UUID.randomUUID().toString().trim().replace("-", "");

        // 给CAS端设置全局门票cookie
        setCookie(COOKIE_USER_TICKET, userTicket, response);

        // 4.userTicket关联用户id，放入redis中，代表该用户拥有了全局门票
        redisOperator.set(REDIS_USER_TICKET + ":" + userTicket, userDO.getId());

        // 5.生成临时门票，回跳到调用端网站验证使用，是CAS签发的一个一次性门票
        String tmpTicket = createTmpTicket();

        String url = returnUrl + "?tmpTicket=" + tmpTicket;
        Map<String, String> map = new HashMap<>();
        map.put("returnUrl", url);
        return AjaxResult.success(map);
    }

    @PostMapping("verifyTmpTicket")
    @ResponseBody
    public AjaxResult verifyTmpTicket(String tmpTicket, HttpServletRequest request, HttpServletResponse response) {
        // 使用一次性临时门票来验证用户是否登录，如果登录过，把用户会话信息返回给站点
        String tmpTicketValue = redisOperator.get(REDIS_TMP_TICKET + ":" + tmpTicket);
        if (StringUtils.isBlank(tmpTicketValue)) {
            return AjaxResult.error("用户门票异常");
        }

        // 如果临时门票成功获取，则销毁门票，并且拿到CAS端cookie中的全局门票，以此获取用户会话信息
        if (!tmpTicketValue.equals(tmpTicket)) {
            return AjaxResult.error("用户门票异常");
        } else {
            // 销毁临时门票
            redisOperator.del(REDIS_TMP_TICKET + ":" + tmpTicket);
        }

        // 验证全局门票绑定的用户会话信息是否存在
        String userTicket = getCookie(request, COOKIE_USER_TICKET);
        String userId = redisOperator.get(REDIS_USER_TICKET + ":" + userTicket);
        if (StringUtils.isBlank(userId)) {
            return AjaxResult.error("用户门票异常");
        }
        String userVO = redisOperator.get(REDIS_USER_INFO + ":" + userId);
        if (StringUtils.isBlank(userVO)) {
            return AjaxResult.error("用户门票异常");
        }

        // 验证成功，返回用户会话信息
        return AjaxResult.success(JsonUtils.jsonToPojo(userVO, UserVO.class));
    }

    @PostMapping("logout")
    @ResponseBody
    public AjaxResult logout(String userId, HttpServletRequest request, HttpServletResponse response) {
        String userTicket = getCookie(request, COOKIE_USER_TICKET);
        // 清除全局门票cookie信息
        clearCookie(COOKIE_USER_TICKET, response);
        // 清除全局门票redis信息
        redisOperator.del(REDIS_USER_TICKET + ":" + userTicket);
        // 清除用户redis会话
        redisOperator.del(REDIS_USER_INFO + ":" + userId);

        return AjaxResult.success();
    }

    /**
     * 创建临时票据
     *
     * @return tmpTicket
     */
    private String createTmpTicket() {
        String tmpTicket = UUID.randomUUID().toString().trim().replace("-", "");
        redisOperator.set(REDIS_TMP_TICKET + ":" + tmpTicket, tmpTicket, 600);
        return tmpTicket;
    }

    /**
     * 设置cookie
     *
     * @param key
     * @param value
     * @param response
     */
    private void setCookie(String key, String value, HttpServletResponse response) {
        Cookie cookie = new Cookie(key, value);
        cookie.setDomain("cas.com");
        cookie.setPath("/");
        response.addCookie(cookie);
    }

    /**
     * 获取对应cookie
     *
     * @param request
     * @param key
     * @return
     */
    private String getCookie(HttpServletRequest request, String key) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || StringUtils.isBlank(key)) {
            return null;
        }

        String cookieValue = null;
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(key)) {
                cookieValue = cookie.getValue();
                break;
            }
        }
        return cookieValue;
    }

    private void clearCookie(String key, HttpServletResponse response) {
        Cookie cookie = new Cookie(key, null);
        cookie.setDomain("cas.com");
        cookie.setPath("/");
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
    }
}
