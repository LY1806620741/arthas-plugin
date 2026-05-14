# arthas 插件

这是一个基于 Arthas 4.1.7 的扩展包，主要面向日常排障和线上辅助操作，当前包含：

- `mock`：在不重启应用的前提下，临时改方法返回值或行为
- `springboot`：给 Spring Boot / Spring MVC 应用动态注入一个 HTTP 转发入口
- `ObjectView` 增强：过滤 Jacoco 注入字段，减少对象查看时的干扰

## 快速了解

### `mock` 能做什么

- 临时覆盖某个方法的返回值
- 在方法执行前或执行后执行 OGNL 表达式
- 快速撤销单个 mock，或一次性清空全部 mock

### `springboot` 能做什么

- 检查当前 JVM 是否存在 Spring MVC Controller
- 如果存在，则动态注入一条路由，例如 `/arthas/**`
- 将这条路由收到的请求转发到 Arthas HTTP 端口
- 可选开启 AES 加密转发

## 获取产物

构建完成后，文件位于 `arthas-plugin/target/`。

对大多数使用者来说，通常只需要关注这两个：

- `arthas-bin.zip`：可直接使用的 Arthas 二进制包，内部已经带上增强后的能力
- `original-arthas-plugin-0.0.1.jar`：用于对你手头的官方 Arthas 解压目录做二次增强

另外还会看到一些构建过程产物，例如：

- `arthas-core.jar`
- `arthas-plugin-0.0.1.jar`

这两个一般不需要单独分发，也通常不是最终使用入口。

如果你是从源码构建，可以在项目根目录执行：

```zsh
cd "/Users/aiden/IdeaProjects/github/arthas-plugin"
mvn -pl arthas-plugin verify
```

## mock 命令

增强后的 Arthas 中可以直接查看帮助：

```text
help mock
```

### 常见用法

```text
# before：方法执行前直接改返回值 / 跳过原逻辑
mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)'

# after：方法执行完成后改返回值
mock demo.MathGame primeFactors -a '#this.returnObj=null'

# 清除单个 mock
mock demo.MathGame primeFactors --clear

# 清除全部 mock
mock --clear-all
```

### 使用提示

- `-b` 适合“执行前拦截”场景
- `-a` 适合“保留原逻辑，但修改最终结果”场景
- `--clear` 和 `--clear-all` 可用于快速回滚临时 mock

## strict 模式说明

`mock` 中的 OGNL 表达式遵循 Arthas 默认 strict 语义，不会自动替你关闭 strict。

这意味着：

- 默认情况下，Arthas 的 `strict` 为 `true`
- 如果表达式里包含属性赋值，例如 `#this.returnObj=...`
- 你需要先手动执行 `options strict false`

推荐顺序：

```text
options strict false
mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)'
```

如果你直接执行赋值类表达式，而没有先关闭 strict，通常会看到类似提示：

```text
By default, strict mode is true, not allowed to set object properties.
Want to set object properties, execute `options strict false`
```

## springboot 命令

`springboot` 是独立命令工具集，不会和 `mock` 混在一起使用。

当前已提供 `--proxy-http` 功能：

- 检查当前 JVM 中是否存在 Spring MVC Controller
- 若存在，则动态注入一条 Spring MVC 路由
- 将该路由下的请求转发到 Arthas HTTP 端口
- 默认转发目标：`127.0.0.1:8563`
- 可选启用 AES/CBC/PKCS5Padding 加密
- 默认参数：key=`arthas`，iv=`0123456789abcdef`

### 常见用法

```text
# 使用默认路由 /arthas/** -> 127.0.0.1:8563
springboot --proxy-http

# 自定义路由和端口
springboot --proxy-http --route /arthas/** --target-port 8563

# 自定义目标地址
springboot --proxy-http --route /arthas/** --target-host 127.0.0.1 --target-port 8563

# 开启 AES 加密转发
springboot --proxy-http --encrypt --encrypt-key arthas --encrypt-iv 0123456789abcdef
```

执行成功后，输出类似：

```text
Route injected successfully: /arthas/** -> 127.0.0.1:8563
```

如果当前 JVM 里没有可用的 Spring MVC Controller，则会提示：

```text
No Spring Boot controllers found in current JVM.
```

## 如何增强官方 Arthas

如果你手里已经有一份官方 `arthas-bin.zip`，也可以直接使用 `original-arthas-plugin-0.0.1.jar` 对解压后的 `arthas-core.jar` 做增强：

```zsh
cd "/path/to/unpacked/arthas-bin"
java -jar original-arthas-plugin-0.0.1.jar
```

执行完成后：

- 当前目录下的 `arthas-core.jar` 会被替换为增强版本
- 原始文件会备份为 `arthas-core.jar.bak`

## 常见问题

### 1. `mock` 没有生效怎么办？

建议按顺序检查：

1. 类名和方法名是否匹配
2. 是否需要先执行 `options strict false`
3. 是否已通过 `--clear` / `--clear-all` 清掉旧规则后重新设置
4. 先执行 `help mock`，确认当前使用的是增强后的 Arthas

### 2. `springboot --proxy-http` 为什么没有注入成功？

优先检查：

1. 当前 JVM 是否真的是 Spring Boot / Spring MVC Web 应用
2. 是否存在可被发现的 Controller
3. 目标 Arthas HTTP 端口是否已启动并可访问

### 3. 需要通过文本方式传输插件包怎么办？

可以用 base64：

```zsh
base64 < original-arthas-plugin-0.0.1.jar | tr -d '\n'
echo "..." | base64 -d > original-arthas-plugin-0.0.1.jar
```

## 补充：源码构建与验证

如果你需要自行构建或做回归验证，可以使用下面这些命令：

```zsh
# 仅编译
mvn -pl arthas-plugin -DskipTests compile

# 运行 mock 相关单测
mvn -pl arthas-plugin -Dtest=MockCommandTest test

# 运行 springboot 命令相关单测
mvn -pl arthas-plugin -Dtest=SpringBootCommandTest test

# 运行制品级回归验证
mvn -pl arthas-plugin -Dit.test=ArthasBootIntegrationIT verify
```

