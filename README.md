# 前后端分离的单点登录系统

## 项目说明

有两个外部系统想要做单点登录，有一个单点登录认证中心系统 **CAS**。

CAS 采用前后端分离架构，后端使用 Spring Boot，前端使用 nginx 做代理。

## 环境配置

### hosts

```
127.0.0.1 www.cas.com
127.0.0.1 www.app1.com
127.0.0.1 www.app2.com
```

### nginx

```
server {
    listen       80;
    server_name  www.cas.com;
    root   html/cas;
}

server {
    listen       80;
    server_name  www.app1.com;

    location / {
        root   html/app1;
        index  index.html index.htm;
	}
}

server {
    listen       80;
    server_name  www.app2.com;

    location / {
        root   html/app2;
        index  index.html index.htm;
    }
}
```

### redis

本地 redis 环境

## 使用

启动后端Spring Boot，前端 nginx。

浏览器输入`www.app1.com`，登录后，另起一个窗口，输入`www.app2.com`，无需登录。

注销也是同理。

## 单点登录的流程

如图所示。

![](http://img.feichaoyu.com/sso.png)

解释说明（序号与图中不对应）：

**客户端访问系统1**

1、浏览器输入`www.app1.com`初次访问，发现没有登录，此时携带你想访问的地址（`www.app1.com`）跳转到 CAS，如下代码所示。

```javascript
window.location.href = "http://www.cas.com:9000/login?returnUrl=http://www.app1.com"
```

2、如果之前没有登录，是第一次的登录的话，跳转到 CAS 登录界面。

CAS`login`接口的代码如下。

```java
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
```

我们只需要关注最后一行，因为使用了 nginx 做代理，所以访问`http://www.cas.com`时，会跳转到默认首页。同时会附带一个参数`returnUrl`。

3、跳转到 CAS 的登录页后，输入账号密码，点击登录。登录接口代码如下。

```java
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
```

4、创建用户会话，以下代码同上。

```java
redisOperator.set(REDIS_USER_INFO + ":" + userDO.getId(), JsonUtils.objectToJson(userVO));
```

5、创建用户全局门票。

```java
 String userTicket = UUID.randomUUID().toString().trim().replace("-", "");
```

6、保存全局门票到 cookie。

```java
 setCookie(COOKIE_USER_TICKET, userTicket, response);
```

7、全局门票关联到用户会话。

```java
 redisOperator.set(REDIS_USER_TICKET + ":" + userTicket, userDO.getId());
```

8、创建临时门票。

```java
String tmpTicket = createTmpTicket();
```

9、拿着临时门票回调到之前想要访问的网址（`www.app1.com`）。

```java
String url = returnUrl + "?tmpTicket=" + tmpTicket;
Map<String, String> map = new HashMap<>();
map.put("returnUrl", url);
return AjaxResult.success(map);
```

具体的回调是前端页面发起得，代码如下。

```javascript
if (res.data.code === 200) {
	var returnUrl = res.data.data.returnUrl;
	window.location.href = returnUrl
}
```

10、校验临时门票，接口如下。

```java
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
```

校验请求时由`www.app1.com`的首页发起的，

```javascript
axios.post('http://www.cas.com:9000/verifyTmpTicket?tmpTicket=' + tmpTicket)
```

11、如果临时门票存在且有效，则销毁临时门票，同时通过全局门票获取用户会话。

12、返回会话到请求端，请求端保存会话到本地 cookie，以便后续使用。

13、显示登录成功。



**在客户端访问完系统1后，客户端又访问系统2**

1、浏览器输入`www.app2.com`初次访问，此时携带你想访问的地址（`www.app1.com`）跳转到 CAS 的 `login`接口校验登录状态。

```java
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
```

2、由于之前系统1登录过了，因此能够从 cookie 中获取到全局门票，只要全局门票验证合法，则创建一个临时门票用于访问。

3、同样需要校验临时门票，校验通过， 就会获取用户会话，返回会话到请求端，显示登录成功。

也就是说，系统2登录时是不需要输入账号密码的。



完整代码地址：https://github.com/FeiChaoyu/CAS

公众号

![](https://cdn.jsdelivr.net/gh/FeiChaoyu/cdn/img/%E5%BE%AE%E4%BF%A1%E5%85%AC%E4%BC%97%E5%8F%B7.png)





