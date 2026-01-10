# this help
default:
  just --list

version  := "4.1.4"

# 加载arthas源码到本地
get-arthas-code:
  test -d arthas || (git clone --depth 1 -b arthas-all-{{version}} https://github.com/alibaba/arthas.git)

# 安装arthas
install-arthas:
  test -d arthas-plugin/.arthas || ((test -f /tmp/arthas-packaging-{{version}}-bin.zip && wget https://repo1.maven.org/maven2/com/taobao/arthas/arthas-packaging/{{version}}/arthas-packaging-{{version}}-bin.zip -P /tmp) && unzip /tmp/arthas-packaging-{{version}}-bin.zip -d arthas-plugin/.arthas)

# 快速启动math-game
start-math:
  java -jar arthas-plugin/.arthas/math-game.jar

# arthas快捷命令
arthas *args:
  java -jar arthas-plugin/.arthas/arthas-boot.jar {{args}}

# 快速启动arthas 附加 math-game
start-arthas:
  java -jar arthas-plugin/.arthas/arthas-boot.jar $(ps -ef |grep math-game | grep -v grep |awk '{print $2}')

#打包为arthas插件
package-plugin:
  cd arthas-plugin && mvn clean package -DskipTests
  mkdir -p arthas-plugin/.arthas/plugins
  cp -f arthas-plugin/target/arthas-plugin-*.jar arthas-plugin/.arthas/plugins/
  mkdir -p arthas-plugin/.arthas/conf && echo "arthas.command.extension=io.github.ly1806620741.arthas.plugin.MockCommand" >> arthas-plugin/.arthas/conf/arthas.properties

# 启动arthas mock命令测试
start-mock-test:
  java -jar arthas-plugin/.arthas/arthas-boot.jar -c "mock" $(ps -ef |grep math-game | grep -v grep |awk '{print $2}')