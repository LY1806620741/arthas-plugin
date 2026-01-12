# this help
default:
  just --list

version  := "4.1.4"

# 加载arthas源码到本地
get-arthas-code:
  test -d arthas || (git clone --depth 1 -b arthas-all-{{version}} https://github.com/alibaba/arthas.git arthas-plugin/.arthas-source)

# 安装arthas
install-arthas:
  test -d arthas-plugin/.arthas || ((test -f /tmp/arthas-packaging-{{version}}-bin.zip && wget https://repo1.maven.org/maven2/com/taobao/arthas/arthas-packaging/{{version}}/arthas-packaging-{{version}}-bin.zip -P /tmp) && unzip /tmp/arthas-packaging-{{version}}-bin.zip -d arthas-plugin/.arthas)

# 快速启动math-game
start-math:
  PID=$(ps -ef |grep math-game | grep -v grep |awk '{print $2}');  [ -n "$PID" ] && kill $PID; java -jar arthas-plugin/.arthas/math-game.jar

# arthas快捷命令
arthas *args:
  java -jar arthas-plugin/.arthas/arthas-boot.jar {{args}}

# 快速启动arthas 附加 math-game
start-arthas *args:
  # arthas-boot.jar是启动器，-cp无法引入jar
  # java -cp arthas-plugin/.arthas/plugins/arthas-plugin-0.0.1.jar:arthas-plugin/.arthas/arthas-boot.jar com.taobao.arthas.boot.Bootstrap {{args}} $(ps -ef |grep math-game | grep -v grep |awk '{print $2}')
  just arthas {{args}} -v $(ps -ef |grep math-game | grep -v grep |awk '{print $2}')
#打包为arthas插件
package-plugin:
  cd arthas-plugin && mvn clean package -DskipTests
  cp -f -p arthas-plugin/target/arthas-plugin-*.jar arthas-plugin/.arthas/
  cd arthas-plugin/.arthas && (test -f arthas-core.old.jar || mv arthas-core.jar arthas-core.old.jar) && mkdir -p temp && cd temp && unzip -qo ../arthas-core.old.jar && unzip -qo ../arthas-plugin-0.0.1.jar '*' -x 'META-INF/**' && zip -qr ../arthas-core.jar ./* && cd - && rm -rf temp && echo -e "\033[32m✅ 合并完成！生成文件: arthas-core.jar\033[0m"

# 启动arthas mock命令测试
start-mock-test:
  # com.taobao.arthas.core.command.BuiltinCommandPack#initCommands
  # just start-arthas -c \"vmtool --action getInstances --className com.taobao.arthas.core.command.BuiltinCommandPack --express \'instances.{commands.{name}}\' -x 2\"
  just start-arthas -c \"mock\"