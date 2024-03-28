# keycloak-services-social-wechat-work

--- 

[🇺🇸 English](README_en-US.md) | **[🇨🇳 简体中文](README.md)**


> Keycloak 企业微信登录插件。相关视频：《[在 Keycloak 中集成企业微信登录演示 - Jeff Tian的视频 - 知乎](https://www.zhihu.com/zvideo/1484138937099190272) 》

## 在线体验

- [点击我，右上角点击登录，然后选择使用企业微信登录](https://keycloak.jiwai.win/realms/UniHeart/account/ )

## 开发

### 构建 package

```shell
mvn clean install
mvn clean package -e -U
```

### 格式化代码

```bash
# format code
mvn com.coveo:fmt-maven-plugin:format
```

### 版本更新

当需要更新本项目的版本时，需要修改 pom.xml 中的版本号。或者使用如下命令，比如将版本号改为 0.5.14：

```shell
mvn versions:set -DnewVersion=0.5.14
```

## 感谢

- 感谢 [ kkzxak47/keycloak-services-social-wechatwork](https://github.com/kkzxak47/keycloak-services-social-wechatwork) 提供的基础代码，本仓库从该仓库 fork 而来。
