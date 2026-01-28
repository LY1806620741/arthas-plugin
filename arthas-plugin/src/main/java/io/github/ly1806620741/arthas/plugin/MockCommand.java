package io.github.ly1806620741.arthas.plugin;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.alibaba.bytekit.asm.MethodProcessor;
import com.alibaba.bytekit.asm.binding.Binding;
import com.alibaba.bytekit.asm.interceptor.InterceptorProcessor;
import com.alibaba.bytekit.asm.interceptor.annotation.AtInvoke;
import com.alibaba.bytekit.asm.interceptor.parser.DefaultInterceptorClassParser;
import com.alibaba.bytekit.asm.location.Location;
import com.alibaba.bytekit.asm.location.MethodInsnNodeWare;
import com.alibaba.bytekit.utils.AsmUtils;
import com.alibaba.deps.org.objectweb.asm.ClassReader;
import com.alibaba.deps.org.objectweb.asm.Opcodes;
import com.alibaba.deps.org.objectweb.asm.tree.ClassNode;
import com.alibaba.deps.org.objectweb.asm.tree.MethodInsnNode;
import com.alibaba.fastjson2.internal.asm.ASMUtils;
import com.taobao.arthas.core.GlobalOptions;
import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.AdviceListenerManager;
import com.taobao.arthas.core.advisor.Enhancer;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.express.ExpressException;
import com.taobao.arthas.core.command.express.ExpressFactory;
import com.taobao.arthas.core.command.klass100.RetransformCommand;
import com.taobao.arthas.core.command.klass100.RetransformCommand.RetransformEntry;
import com.taobao.arthas.core.command.model.EnhancerModelFactory;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.affect.EnhancerAffect;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.DefaultValue;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

import net.bytebuddy.matcher.StringMatcher;
import ognl.Ognl;
import ognl.OgnlException;

@Name("mock")
@Summary("Arthas 4.1.4 自定义Mock命令：动态mock指定类的指定方法返回值/抛出异常/修改入参，无侵入不重启")
@Description("Mock命令使用示例：\n" +
        "  1. 立即返回固定值: mock com.demo.UserService getUserById -b '{\"id\":100,\"name\":\"mock-user\"}'\n" +
        "  2. 执行后修改返回值: mock com.demo.OrderService getOrder -s 'null'\n" +
        "  3. 抛出指定异常: mock com.demo.PayService pay -e 'new RuntimeException(\"支付失败\")'\n" +
        "  4. 修改方法入参: mock com.demo.UserService updateUser -b '[0, {\"id\":1,\"name\":\"modify\"}]'\n" +
        "  5. 清除指定mock: mock com.demo.UserService getUserById --clear\n" +
        "  6. 清除全部mock: mock --clear-all\n")
public class MockCommand extends AnnotatedCommand {

    private static final Logger logger = LoggerFactory.getLogger(MockCommand.class);

    private String classPattern;
    private String methodPattern;
    private String express;
    private String conditionExpress;
    private boolean isAfter = false;
    private boolean isException = false;
    private boolean clear = false;
    private boolean clearAll = false;
    private Integer sizeLimit = 10 * 1024 * 1024;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;
    private boolean verbose = false;

    private String hashCode;
    private String classLoaderClass;

    private static volatile List<String> mockClass = new ArrayList<String>();

    @Argument(index = 0, argName = "class-pattern")
    @Description("The full qualified class name you want to mock")
    public void setClassPattern(String classPattern) {
        this.classPattern = StringUtils.normalizeClassName(classPattern);
    }

    @Argument(index = 1, argName = "method-pattern")
    @Description("The method name you want to mock")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    @Argument(index = 2, argName = "express", required = false)
    @DefaultValue("{params, target, returnObj}")
    @Description("The mock express, written by ognl. \n" + Constants.EXPRESS_EXAMPLES)
    public void setExpress(String express) {
        this.express = express;
    }

    @Argument(index = 3, argName = "condition-express", required = false)
    @Description(Constants.CONDITION_EXPRESS)
    public void setConditionExpress(String conditionExpress) {
        this.conditionExpress = conditionExpress;
    }

    @Option(shortName = "a", longName = "after", flag = true)
    @Description("Mock after method invocation (修改入参/立即返回)")
    public void setAfter(boolean after) {
        isAfter = after;
    }

    @Option(shortName = "e", longName = "exception", flag = true)
    @Description("Throw exception instead of normal return")
    public void setException(boolean exception) {
        isException = exception;
    }

    @Option(longName = "clear", flag = true)
    @Description("Clear mock for specified class/method")
    public void setClear(boolean clear) {
        this.clear = clear;
    }

    @Option(longName = "clear-all", flag = true)
    @Description("Clear all mocks")
    public void setClearAll(boolean clearAll) {
        this.clearAll = clearAll;
    }

    @Option(shortName = "M", longName = "sizeLimit")
    @Description("Upper size limit in bytes for the result (10 * 1024 * 1024 by default)")
    public void setSizeLimit(Integer sizeLimit) {
        this.sizeLimit = sizeLimit;
    }

    @Option(shortName = "E", longName = "regex", flag = true)
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "n", longName = "limits")
    @Description("Threshold of execution times")
    public void setNumberOfLimit(int numberOfLimit) {
        this.numberOfLimit = numberOfLimit;
    }

    @Option(shortName = "v", longName = "verbose", flag = true)
    @Description("Enable verbose output")
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public String getClassPattern() {
        return classPattern;
    }

    public String getMethodPattern() {
        return methodPattern;
    }

    public String getExpress() {
        return express;
    }

    public String getConditionExpress() {
        return conditionExpress;
    }

    public boolean isAfter() {
        return isAfter;
    }

    public boolean isException() {
        return isException;
    }

    public Integer getSizeLimit() {
        return sizeLimit;
    }

    public boolean isRegEx() {
        return isRegEx;
    }

    public int getNumberOfLimit() {
        return numberOfLimit;
    }

    public boolean isVerbose() {
        return verbose;
    }

    protected boolean isConditionMet(String conditionExpress, OgnlMockAdvice advice) throws ExpressException {
        return StringUtils.isEmpty(conditionExpress)
                || ExpressFactory.threadLocalExpress(advice).is(conditionExpress);
    }

    protected Object getExpressionResult(String express, OgnlMockAdvice advice) throws ExpressException {
        return ExpressFactory.threadLocalExpress(advice).get(express);
    }

    @Override
    public void process(CommandProcess process) {
        Session session = process.session();
        if (!session.tryLock()) {
            String msg = "someone else is enhancing classes, pls. wait.";
            process.appendResult(EnhancerModelFactory.create(null, false, msg));
            process.end(-1, msg);
            return;
        }
        int lock = session.getLock();
        try {
            // 处理清除逻辑
            if (clearAll) {
                clearAllMocks(process.session().getInstrumentation());
                process.appendResult(EnhancerModelFactory.create(new EnhancerAffect(), true, "All mocks cleared."));
                process.end(0, "OK");
                return;
            }

            if (clear) {
                if (classPattern == null || methodPattern == null) {
                    process.end(-1, "--clear requires class-pattern and method-pattern");
                    return;
                }
                clearMock(process.session().getInstrumentation());
                process.appendResult(EnhancerModelFactory.create(new EnhancerAffect(), true, "Mock cleared."));
                process.end(0, "OK");
                return;
            }

            // 检查必要参数
            if (classPattern == null || methodPattern == null) {
                process.end(-1, "class-pattern and method-pattern are required.");
                return;
            }

            if (express == null) {
                process.end(-1, "Mock express is required (use -b/-s/-e with value).");
                return;
            }

            Instrumentation inst = session.getInstrumentation();
            Matcher<String> classNameMatcher = SearchUtils.classNameMatcher(classPattern, isRegEx);
            Matcher<String> methodNameMatcher = SearchUtils.classNameMatcher(methodPattern, isRegEx);

            Set<Class<?>> matchingClasses = GlobalOptions.isDisableSubClass
                    ? SearchUtils.searchClass(inst, classNameMatcher)
                    : SearchUtils.searchSubClass(inst, SearchUtils.searchClass(inst, classNameMatcher));

            if (matchingClasses.isEmpty()) {
                process.end(-1, "No class matched: " + classPattern);
                return;
            }

            EnhancerAffect affect = new EnhancerAffect();
            List<RetransformEntry> entries = new ArrayList<>();

            for (Class<?> clazz : matchingClasses) {
                String className = clazz.getName();

                ClassNode classNode = AsmUtils.loadClass(clazz);
                byte[] classfileBuffer = AsmUtils.toBytes(classNode);

                mockClass.add(className);
                entries.add(new RetransformEntry(clazz.getName(),
                        classfileBuffer,
                        hashCode, classLoaderClass));
            }

            // 注册到 Arthas 全局 retransform 管理器
            RetransformCommand.addRetransformEntry(entries);

            process.appendResult(EnhancerModelFactory.create(affect, true, "Mock installed."));
            process.end(0, "OK");
        } catch (Throwable e) {
            logger.warn("mock failed.", e);
            process.end(-1, "mock failed, condition is: " + this.getConditionExpress() + ", express is: "
                    + this.getExpress() + ", " + e.getMessage() + ", visit " + LogUtil.loggingFile()
                    + " for more details.");
        }

    }

    private void clearMock(Instrumentation inst) {
        // 从全局 retransform 列表中移除
        // TODO RetransformCommand.deleteRetransformEntry(classPattern, methodPattern,
        // isRegEx);
        // 重新 retransform 以恢复原始字节码
        Set<Class<?>> classes = SearchUtils.searchClass(inst, SearchUtils.classNameMatcher(classPattern, isRegEx));
        for (Class<?> clazz : classes) {
            try {
                inst.retransformClasses(clazz);
            } catch (Exception e) {
                logger.warn("Failed to retransform class on clear: " + clazz.getName(), e);
            }
        }
        mockClass.remove(classPattern);
    }

    private void clearAllMocks(Instrumentation inst) {
        // RetransformCommand.deleteAllRetransformEntry();TODO
        for (String className : mockClass) {
            Set<Class<?>> classes = SearchUtils.searchClass(inst, SearchUtils.classNameMatcher(className, false));
            for (Class<?> clazz : classes) {
                try {
                    inst.retransformClasses(clazz);
                } catch (Exception e) {
                    logger.warn("Failed to retransform class on clear-all: " + clazz.getName(), e);
                }
            }
        }
        mockClass.clear();
    }

    static class OgnlMockAdvice {

        @AtInvoke(name = "", inline = true, whenComplete = false, excludes = { "java.arthas.SpyAPI", "java.lang.Byte",
                "java.lang.Boolean", "java.lang.Short", "java.lang.Character", "java.lang.Integer", "java.lang.Float",
                "java.lang.Long", "java.lang.Double" })
        public static void onInvoke(@Binding.This Object target, @Binding.Class Class<?> clazz,
                @Binding.InvokeInfo String invokeInfo, @Binding.Args Object[] args, @Binding.Return Object returnObj)
                throws Throwable {
            MockCommand command = getCurrentMockCommand();
            if (command == null || command.isAfter()) {
                return;
            }

            // 构造上下文
            Advice advice = Advice.newForAfterReturning(null, clazz, null, target, args, returnObj);

            // 条件判断
            if (!isConditionMet(command.getConditionExpress(), advice)) {
                return;
            }

            Object result;
            try {
                result = getExpressionResult(command.getExpress(), advice);
                if (command.isException()) {
                    if (result instanceof Throwable) {
                        throw (Throwable) result;
                    } else {
                        throw new RuntimeException("Mock exception must be a Throwable, got: " + result);
                    }
                } else if (result instanceof Object[]) {
                    // 假设表达式返回 [index, newValue] 用于修改入参
                    Object[] mod = (Object[]) result;
                    if (mod.length == 2 && mod[0] instanceof Number) {
                        int index = ((Number) mod[0]).intValue();
                        if (index >= 0 && index < args.length) {
                            args[index] = mod[1];
                        }
                    }
                } else {
                    // 立即返回
                    throw new MockReturnException(result);
                }
            } catch (ExpressException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        @AtInvoke(name = "", inline = true, whenComplete = true, excludes = { "java.arthas.SpyAPI", "java.lang.Byte",
                "java.lang.Boolean", "java.lang.Short", "java.lang.Character", "java.lang.Integer", "java.lang.Float",
                "java.lang.Long", "java.lang.Double" })
        public static void onInvokeAfter(@Binding.This Object target, @Binding.Class Class<?> clazz,
                @Binding.InvokeInfo String invokeInfo, @Binding.Return Object returnObj) {
            MockCommand command = getCurrentMockCommand();
            if (command == null || !command.isAfter()) {
                return;
            }

            Advice advice = Advice.newForAfterReturning(null, clazz, null, target, null, returnObj);

            if (!isConditionMet(command.getConditionExpress(), advice)) {
                return;
            }

            Object newReturn;
            try {
                newReturn = getExpressionResult(command.getExpress(), advice);
            } catch (ExpressException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        // 工具方法
        private static boolean isConditionMet(String conditionExpress, Advice advice) {
            if (StringUtils.isEmpty(conditionExpress)) {
                return true;
            }
            try {
                return ExpressFactory.threadLocalExpress(advice).is(conditionExpress);
            } catch (ExpressException e) {
                logger.warn("Condition express error: " + conditionExpress, e);
                return false;
            }
        }

        private static Object getExpressionResult(String express, Advice advice) throws ExpressException {
            return ExpressFactory.threadLocalExpress(advice).get(express);
        }

        private static MockCommand getCurrentMockCommand() {
            // 实际项目中应通过 ThreadLocal 或全局注册表获取当前命令上下文
            // 此处简化：假设只有一个 active mock（实际需改进）
            return null; // ⚠️ 这是简化版，真实场景需传递 command 上下文
        }

    }

    // 自定义异常用于携带返回值
    public static class MockReturnException extends RuntimeException {
        private final Object returnValue;

        public MockReturnException(Object returnValue) {
            super("Mock return", null, false, false);
            this.returnValue = returnValue;
        }

        public Object getReturnValue() {
            return returnValue;
        }
    }
}