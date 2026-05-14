# arthas 插件

基于 Arthas 4.1.7 的扩展工程，当前主要提供：

- `mock` 自定义命令
- `springboot` 额外springboot命令工具集
- `ObjectView` 中对 Jacoco 注入字段的过滤能力

## 构建

在项目根目录执行：

```zsh
cd "/Users/aiden/IdeaProjects/github/arthas-plugin"
mvn -pl arthas-plugin verify
```

常用命令：

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

## 产物说明

构建完成后，重点产物位于 `arthas-plugin/target/`：

- `arthas-core.jar`：已合并增强类的最终核心包
- `arthas-bin.zip`：已替换内部 `arthas-core.jar` 的最终二进制包
- `arthas-plugin-0.0.1.jar`：当前插件包
- `original-arthas-plugin-0.0.1.jar`：保留原始插件制品，用于对官方 `arthas-core.jar` 做二次增强

## mock 命令说明

项目会把 `MockCommand` 注册进 Arthas 内置命令集合，因此增强后的 Arthas 可直接执行：

```text
help mock
```

支持的典型用法：

```text
# before：直接改返回值 / 跳过原逻辑
mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)'

# after：执行完成后改返回值
mock demo.MathGame primeFactors -a '#this.returnObj=null'

# 清除单个 mock
mock demo.MathGame primeFactors --clear

# 清除全部 mock
mock --clear-all
```

## strict 模式说明

`mock` 命令中的 OGNL 表达式遵循 Arthas 默认 strict 语义：

- 默认情况下，Arthas 的 `strict` 为 `true`
- 当表达式涉及对象属性写入、`#this.returnObj=...` 之类赋值时，用户需要**手动**关闭 strict
- 插件不会自动帮用户关闭 strict

推荐顺序：

```text
options strict false
mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)'
```

如果未关闭 strict，运行时应看到 Arthas 默认提示，类似：

```text
By default, strict mode is true, not allowed to set object properties.
Want to set object properties, execute `options strict false`
```

## springboot 命令说明

`springboot` 是独立工具集。当前提供 `--proxy-http` 能力：

- 检查当前 JVM 中是否存在 Spring MVC Controller
- 若存在，则向 Spring MVC 动态注入一条路由
- 将该路由下的请求转发到 Arthas HTTP 端口（默认 `127.0.0.1:8563`）
- 可选开启 AES 转发加密，默认 key=`arthas`，iv=`0123456789abcdef`

典型用法：

```text
# 使用默认路由 /arthas/** -> 127.0.0.1:8563
springboot --proxy-http

# 自定义转发路由与 Arthas HTTP 端口
springboot --proxy-http --route /arthas/** --target-port 8563

# 开启 AES 加密转发
springboot --proxy-http --encrypt --encrypt-key arthas --encrypt-iv 0123456789abcdef
```

执行成功后会返回类似结果：

```text
Route injected successfully: /arthas/** -> 127.0.0.1:8563
```

## 官方 arthas-core 增强方式

如果你已经有一份官方 `arthas-bin.zip`，也可以使用 `original-arthas-plugin-0.0.1.jar` 在解压目录内直接增强其中的 `arthas-core.jar`：

```zsh
cd "/path/to/unpacked/arthas-bin"
java -jar original-arthas-plugin-0.0.1.jar
```

执行完成后，当前目录下的 `arthas-core.jar` 会被增强，原始文件会备份为：

```text
arthas-core.jar.bak
```

## 上传扩展

如果需要通过文本方式传输插件包，可使用 base64：

```zsh
base64 < original-arthas-plugin-0.0.1.jar | tr -d '\n'
echo "..." | base64 -d > original-arthas-plugin-0.0.1.jar
```
