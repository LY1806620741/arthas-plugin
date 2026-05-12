package io.github.ly1806620741.arthas.plugin;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.taobao.arthas.core.GlobalOptions;
import org.benf.cfr.reader.util.Optional;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.alibaba.bytekit.utils.Decompiler;
import com.alibaba.deps.org.objectweb.asm.Opcodes;
import com.alibaba.deps.org.objectweb.asm.tree.MethodNode;
import com.taobao.arthas.common.ReflectUtils;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.command.express.ExpressException;
import com.taobao.arthas.core.command.express.ExpressFactory;
import com.taobao.arthas.core.command.klass100.RetransformCommand;
import com.taobao.arthas.core.command.klass100.RetransformCommand.RetransformEntry;
import com.taobao.arthas.core.command.model.EnhancerModelFactory;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.util.ArthasCheckUtils;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.affect.EnhancerAffect;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

import io.github.ly1806620741.arthas.OgnlContext;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;

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
    private String beforeOgnl;
    private String afterOgnl;
    private boolean isException = false;
    private boolean clear = false;
    private boolean clearAll = false;
    private Integer sizeLimit = 10 * 1024 * 1024;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;
    private boolean verbose = false;

    private String hashCode;
    private String classLoaderClass;
    private static volatile List<Class<?>> mockClass = new ArrayList<>();

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

    @Option(shortName = "b", longName = "beforeOgnl")
    @Description("Mock before method invocation (修改入参/立即返回)")
    public void setBeforeOgnl(String beforeOgnl) {
        this.beforeOgnl = beforeOgnl;
    }

    // @Argument(index = 3, argName = "condition-express", required = false)
    // @Description(Constants.CONDITION_EXPRESS)
    // public void setConditionExpress(String conditionExpress) {
    // this.conditionExpress = conditionExpress;
    // }

    @Option(shortName = "a", longName = "afterOgnl")
    @Description("Mock after method invocation (修改入参/立即返回)")
    public void setAfterOgnl(String afterOgnl) {
        this.afterOgnl = afterOgnl;
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

    public String getBeforeOgnl() {
        return beforeOgnl;
    }

    public String getAfterOgnl() {
        return afterOgnl;
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

    private boolean isIgnore(MethodNode methodNode, Matcher<String> methodNameMatcher) {
        return null == methodNode || isAbstract(methodNode.access) || !methodNameMatcher.matching(methodNode.name)
                || ArthasCheckUtils.isEquals(methodNode.name, "<clinit>");
    }

    /**
     * 是否抽象属性
     */
    private boolean isAbstract(int access) {
        return (Opcodes.ACC_ABSTRACT & access) == Opcodes.ACC_ABSTRACT;
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

                OgnlMockAdvice.putMock(clazz, this);
                byte[] enhancedBytes = new ByteBuddy()
                        .redefine(clazz)
                        .visit(net.bytebuddy.asm.Advice.to(OgnlMockAdvice.class)
                                .on(ElementMatchers.namedOneOf(OgnlMockAdvice.getMockedMethods(clazz))))
                        .make()
                        .getBytes();

                if (verbose) {
                    logger.info("Enhanced class {} methods {}:\n{}", clazz.getName(),
                            OgnlMockAdvice.getMockedMethods(clazz), Decompiler.decompile(enhancedBytes));
                }
                if (!mockClass.contains(clazz)) {
                    mockClass.add(clazz);
                }
                entries.add(new RetransformEntry(clazz.getName(),
                        enhancedBytes,
                        hashCode, classLoaderClass));
            }

            // 注册到 Arthas 全局 retransform 管理器
            Method method = ReflectUtils.findMethod(
                    "com.taobao.arthas.core.command.klass100.RetransformCommand.initTransformer()");
            method.setAccessible(true);
            method.invoke(null);
            RetransformCommand.addRetransformEntry(entries);
            inst.retransformClasses(matchingClasses.toArray(new Class[0]));

            process.appendResult(EnhancerModelFactory.create(affect, true, "Mock installed."));
            process.end(0, "OK");
        } catch (Throwable e) {
            logger.warn("mock failed.", e);
            process.end(-1, "mock failed, beforeOgnl is: " + this.getBeforeOgnl() + ", afterOgnl is: "
                    + this.getAfterOgnl() + ", " + e.getMessage() + ", visit " + LogUtil.loggingFile()
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
            OgnlMockAdvice.removeMock(clazz);
            mockClass.remove(clazz);
        }
    }

    private void clearAllMocks(Instrumentation inst) {
        // RetransformCommand.deleteAllRetransformEntry();TODO
        for (Class<?> className : new ArrayList<Class<?>>(mockClass)) {
            Set<Class<?>> classes = SearchUtils.searchClass(inst,
                    SearchUtils.classNameMatcher(className.getName(), false));
            for (Class<?> clazz : classes) {
                try {
                    inst.retransformClasses(clazz);
                } catch (Exception e) {
                    logger.warn("Failed to retransform class on clear-all: " + clazz.getName(), e);
                }
                OgnlMockAdvice.removeMock(clazz);
            }
            mockClass.remove(className);
        }
    }

    static RuntimeException propagateMockException(Throwable throwable) {
        Throwable cause = throwable.getCause();
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        return new RuntimeException(throwable);
    }

    public static class OgnlMockAdvice {

        static Map<Class<?>, Map<String, MockCommand>> mockCommands = new ConcurrentHashMap<>();

        public static void putMock(Class<?> clz, MockCommand mockCommand) {
            mockCommands.computeIfAbsent(clz, key -> new ConcurrentHashMap<>())
                    .put(mockCommand.getMethodPattern(), mockCommand);
        }

        public static void removeMock(Class<?> clz) {
            mockCommands.remove(clz);
        }

        public static String[] getMockedMethods(Class<?> clz) {
            Map<String, MockCommand> methodMocks = mockCommands.get(clz);
            if (methodMocks == null || methodMocks.isEmpty()) {
                return new String[0];
            }
            return methodMocks.keySet().toArray(new String[0]);
        }

        @net.bytebuddy.asm.Advice.OnMethodEnter(inline = true, skipOn = net.bytebuddy.asm.Advice.OnDefaultValue.class)
        public static Object onInvokeBefore(
                @net.bytebuddy.asm.Advice.This(optional = true) Object target,
                @net.bytebuddy.asm.Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args,
                @net.bytebuddy.asm.Advice.Origin Class<?> clazz,
                @net.bytebuddy.asm.Advice.Origin("#m") String methodName,
                @net.bytebuddy.asm.Advice.Local("ognlContext") OgnlContext ognlContext) throws Throwable {

            ognlContext = OgnlMockAdvice.invoke(target, clazz, methodName, args, false);
            if (ognlContext == null || !Boolean.TRUE.equals(ognlContext.skip)) {
                return Boolean.TRUE;
            }
            return null;
        }

        public static OgnlContext invoke(Object target,
                Class<?> clazz,
                String methodName,
                Object[] args,
                boolean isAfter) {
            MockCommand mockCommand = getMockCommand(clazz, methodName);
            if (isAfter && target instanceof OgnlContext) {
                OgnlContext existingContext = (OgnlContext) target;
                if (clazz == null) {
                    clazz = existingContext.getClazz();
                }
                if (methodName == null && existingContext.getMethod() != null) {
                    methodName = existingContext.getMethod().getName();
                }
                mockCommand = getMockCommand(clazz, methodName);
            }
            if (mockCommand == null) {
                return null;
            }

            OgnlContext ognlContext;
            String express;
            if (isAfter) {
                ognlContext = (OgnlContext) target;
                if (mockCommand.getAfterOgnl() == null) {
                    return ognlContext;
                }
                express = mockCommand.getAfterOgnl();
            } else {
                ognlContext = OgnlContext.init(clazz.getClassLoader(), clazz, resolveArthasMethod(clazz, methodName),
                        target, args, null);
                if (mockCommand.getBeforeOgnl() == null) {
                    return ognlContext;
                }
                express = mockCommand.getBeforeOgnl();
            }

            try {
                getExpressionResult(express, ognlContext);
            } catch (Throwable e) {
                throw MockCommand.propagateMockException(e);
            }

            return ognlContext;
        }

        @net.bytebuddy.asm.Advice.OnMethodExit(inline = true, onThrowable = Throwable.class)
        public static void onInvokeAfter(
                @net.bytebuddy.asm.Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                @net.bytebuddy.asm.Advice.Origin Class<?> clazz,
                @net.bytebuddy.asm.Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable thrown,
                @net.bytebuddy.asm.Advice.Local("ognlContext") OgnlContext ognlContext) {
            if (ognlContext == null) {
                return;
            }

            ognlContext.setOriginReturnObj(returned);
            // Keep before-mock return value when the original method is skipped.
            if (!Boolean.TRUE.equals(ognlContext.skip)) {
                ognlContext.setReturnObj(returned);
            }
            ognlContext.setThrowExp(thrown);

            OgnlMockAdvice.invoke(ognlContext, clazz, null, null, true);

            if (!Optional.empty().equals(ognlContext.returnObj)) {
                returned = ognlContext.returnObj;
                thrown = null;
            }
            if (ognlContext.getThrowExp() != null) {
                thrown = ognlContext.getThrowExp();
            }
        }

        private static Object getExpressionResult(String express, OgnlContext ognlContext) throws ExpressException {
            return ExpressFactory.threadLocalExpress(ognlContext).get(express);
        }

        private static MockCommand getMockCommand(Class<?> clazz, String methodName) {
            if (clazz == null || methodName == null) {
                return null;
            }
            Map<String, MockCommand> methodMocks = mockCommands.get(clazz);
            if (methodMocks == null) {
                return null;
            }
            return methodMocks.get(methodName);
        }

        private static ArthasMethod resolveArthasMethod(Class<?> clazz, String methodName) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return new ArthasMethod(clazz, methodName, descriptor(method.getParameterTypes()));
                }
            }
            throw new IllegalArgumentException("No method named '" + methodName + "' found on " + clazz.getName());
        }

        private static String descriptor(Class<?>[] parameterTypes) {
            StringBuilder builder = new StringBuilder();
            for (Class<?> parameterType : parameterTypes) {
                builder.append(toDescriptor(parameterType));
            }
            return builder.toString();
        }

        private static String toDescriptor(Class<?> type) {
            if (type.isPrimitive()) {
                if (type == void.class) {
                    return "V";
                }
                if (type == boolean.class) {
                    return "Z";
                }
                if (type == byte.class) {
                    return "B";
                }
                if (type == char.class) {
                    return "C";
                }
                if (type == short.class) {
                    return "S";
                }
                if (type == int.class) {
                    return "I";
                }
                if (type == long.class) {
                    return "J";
                }
                if (type == float.class) {
                    return "F";
                }
                if (type == double.class) {
                    return "D";
                }
            }
            if (type.isArray()) {
                return type.getName().replace('.', '/');
            }
            return "L" + type.getName().replace('.', '/') + ";";
        }

        private static RuntimeException propagateMockException(Throwable throwable) {
            return MockCommand.propagateMockException(throwable);
        }
    }
}
