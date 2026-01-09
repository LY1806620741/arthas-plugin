package io.github.ly1806620741.arthas.plugin;

import com.taobao.arthas.core.command.Command;
import com.taobao.arthas.core.command.CommandMapping;
import com.taobao.arthas.core.shell.command.CommandProcess;

import java.util.List;

/**
 * Arthas 自定义Mock命令主类
 * 命令使用方式和原生命令一致，支持：
 * 1. mock 全类名 方法名 mock值       - 基础mock返回值
 * 2. mock 全类名 方法名 -e 异常信息   - mock方法抛异常
 * 3. mock --clear 全类名 方法名       - 清除指定方法的mock
 * 4. mock --clear-all                - 清除所有mock
 */
@CommandMapping(
        name = "mock", // Arthas控制台执行的命令名 ✅ 核心
        description = "Arthas自定义Mock命令：动态mock指定类的指定方法返回值/异常",
        example = {
                "mock com.demo.service.UserService getUserById 1001",
                "mock com.demo.service.OrderService createOrder null",
                "mock com.demo.service.PayService pay -e 支付失败:余额不足",
                "mock --clear com.demo.service.UserService getUserById",
                "mock --clear-all"
        },
        synopsis = "mock [--clear] [--clear-all] [className] [methodName] [mockValue/-e exceptionMsg]"
)
public class MockCommand extends Command { // 必须继承Arthas的Command抽象类 ✅ 核心

    @Override
    public void process(CommandProcess process) {
        try {
            List<String> args = process.args();
            if (args.isEmpty()) {
                process.write("❌ 请输入mock参数！输入 mock -h 查看帮助\n");
                process.end();
                return;
            }

            // 处理命令参数
            String result = handleMockArgs(args);
            process.write(result + "\n");
        } catch (Exception e) {
            process.write("❌ Mock命令执行异常：" + e.getMessage() + "\n");
        } finally {
            process.end(); // 必须结束命令，否则Arthas控制台阻塞 ✅ 核心
        }
    }

    /**
     * 解析mock命令参数，分发不同的处理逻辑
     */
    private String handleMockArgs(List<String> args) {
        if ("--clear-all".equalsIgnoreCase(args.get(0))) {
            // 清除所有mock
            return MockEnhancer.clearAllMock();
        } else if ("--clear".equalsIgnoreCase(args.get(0))) {
            // 清除指定方法的mock
            if (args.size() < 4) {
                return "❌ 清除mock参数不足！示例：mock --clear com.demo.UserService getUserById";
            }
            String className = args.get(1);
            String methodName = args.get(2);
            return MockEnhancer.clearMock(className, methodName);
        } else if ("-e".equalsIgnoreCase(args.get(2))) {
            // mock方法抛出异常
            if (args.size() < 4) {
                return "❌ 异常mock参数不足！示例：mock com.demo.PayService pay -e 支付失败";
            }
            String className = args.get(0);
            String methodName = args.get(1);
            String exceptionMsg = args.get(3);
            return MockEnhancer.mockMethod(className, methodName, null, exceptionMsg);
        } else {
            // 基础mock返回值
            if (args.size() < 3) {
                return "❌ Mock参数不足！示例：mock com.demo.UserService getUserById 1001";
            }
            String className = args.get(0);
            String methodName = args.get(1);
            String mockValue = args.get(2);
            return MockEnhancer.mockMethod(className, methodName, mockValue, null);
        }
    }
}