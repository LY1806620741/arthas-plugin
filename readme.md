# arthas 插件

一个基于 Arthas 4.1.7 的扩展包，提供两个实用命令：

- `mock`：动态修改方法行为
- `springboot`：给 Spring Boot / Spring MVC 应用注入 HTTP 代理路由

同时还增强了 `ObjectView`，可忽略 Jacoco 注入字段。

## 你会得到什么

构建或发布后，通常只需要关注这个文件：

- `arthas-plugin-0.0.1.jar`

它的作用是：**增强你手头官方 Arthas 解压目录中的 `arthas-core.jar`**。

## 如何使用

先准备一份官方 `arthas-bin.zip`，解压后进入目录，执行：

```zsh
java -jar arthas-plugin-0.0.1.jar
```

执行完成后：

- 当前目录下的 `arthas-core.jar` 会被替换为增强版本
- 原始文件会备份为 `arthas-core.jar.bak`

之后像平常一样启动 Arthas 即可。

## mock 命令

查看帮助：

```text
help mock
```

常见示例：

```text
# 方法执行前直接改返回值
mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)'

# 方法执行后改返回值
mock demo.MathGame primeFactors -a '#this.returnObj=null'

# 清除单个 mock
mock demo.MathGame primeFactors --clear

# 清除全部 mock
mock --clear-all
```

### strict 提示

`mock` 里的 OGNL 表达式遵循 Arthas 默认 strict 语义。

如果表达式包含赋值，例如：

```text
#this.returnObj=...
```

通常需要先执行：

```text
options strict false
```

否则会看到 Arthas 默认提示：

```text
By default, strict mode is true, not allowed to set object properties.
Want to set object properties, execute `options strict false`
```

## springboot 命令

查看帮助：

```text
help springboot
```

当前提供 `--proxy-http`：

- 检查当前 JVM 是否存在 Spring MVC Controller
- 若存在，则动态注入一条 Spring MVC 路由
- 将该路由下的请求转发到 Arthas HTTP 端口

常见示例：

```text
# 默认路由 /arthas/** -> 127.0.0.1:8563
springboot --proxy-http

# 自定义路由和目标端口
springboot --proxy-http --route /arthas/** --target-port 8563

# 自定义目标地址
springboot --proxy-http --route /arthas/** --target-host 127.0.0.1 --target-port 8563

# 开启 AES 加密转发
springboot --proxy-http --encrypt --encrypt-key arthas --encrypt-iv 0123456789abcdef
```

成功时输出类似：

```text
Route injected successfully: /arthas/** -> 127.0.0.1:8563
```

如果未找到可用 Controller，则会提示：

```text
No Spring Boot controllers found in current JVM.
```

## 常见问题

### `mock` 没生效？

优先检查：

1. 类名、方法名是否匹配
2. 是否需要先执行 `options strict false`
3. 是否已用 `--clear` / `--clear-all` 清掉旧规则
4. 当前是否确实在使用增强后的 Arthas

### `springboot --proxy-http` 没注入成功？

优先检查：

1. 当前 JVM 是否为 Spring Boot / Spring MVC Web 应用
2. 是否存在可被发现的 Controller
3. 目标 Arthas HTTP 端口是否已启动并可访问

## 构建

如果你需要自己构建：

```zsh
cd "/Users/aiden/IdeaProjects/github/arthas-plugin"
mvn -pl arthas-plugin verify
```

常用验证命令：

```zsh
# mock 单测
mvn -pl arthas-plugin -Dtest=MockCommandTest test

# springboot 单测
mvn -pl arthas-plugin -Dtest=SpringBootCommandTest test

# 制品级集成测试
mvn -pl arthas-plugin -Dit.test=ArthasBootIntegrationIT verify
```

