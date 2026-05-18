package io.github.ly1806620741.arthas.plugin;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

import com.taobao.arthas.core.GlobalOptions;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.alibaba.bytekit.utils.Decompiler;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.taobao.arthas.common.ReflectUtils;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.command.express.ExpressException;
import com.taobao.arthas.core.command.express.ExpressFactory;
import com.taobao.arthas.core.command.klass100.RetransformCommand;
import com.taobao.arthas.core.command.klass100.RetransformCommand.RetransformEntry;
import com.taobao.arthas.core.command.model.EnhancerModelFactory;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.session.Session;
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
import ognl.OgnlRuntime;

@Name("mock")
@Summary("Arthas 4.1.4 自定义Mock命令：动态mock指定类的指定方法返回值/抛出异常/修改入参，无侵入不重启")
@Description("Mock命令使用示例：\n" +
        "  1. 立即返回固定值: mock com.demo.UserService getUserById -b '{\"id\":100,\"name\":\"mock-user\"}'\n" +
        "  2. 执行后修改返回值: mock com.demo.OrderService getOrder -s 'null'\n" +
        "  3. 抛出指定异常: mock com.demo.PayService pay -e 'new RuntimeException(\"支付失败\")'\n" +
        "  4. 修改方法入参: mock com.demo.UserService updateUser -b '[0, {\"id\":1,\"name\":\"modify\"}]'\n" +
        "  5. 清除指定mock: mock com.demo.UserService getUserById --clear\n" +
        "  6. 查看当前mock列表: mock --list\n" +
        "  7. 清除全部mock: mock --clear-all\n" +
        "  8. -j 预载JSON后修改返回值: mock com.demo.UserService getUser -j '{\"id\":1,\"profile\":{\"name\":\"arthas\"}}' -a '#json.profile.name=\"changed\",#this.returnObj=#json'\n" +
        "  9. -j 预载JSON后替换单个对象入参: mock com.demo.UserService save -j '{\"profile\":{\"name\":\"arthas\"}}' -b '#json.profile.name=\"patched\",#this.params[0]=#json,#this.skip=false'\n" +
        " 10. -j 预载JSON数组后替换多个入参: mock com.demo.OrderService create -j '[\"mock\", {\"id\":1}]' -b '#this.params[0]=#json[0],#this.params[1]=#json[1],#this.skip=false'\n" +
        " 11. JSON支持@type指定具体对象类型: mock com.demo.AnimalService load -j '{\"@type\":\"com.demo.Dog\",\"name\":\"arthas\"}' -a '#this.returnObj=#json'\n" +
        " 12. 双层多态预解析示例(外层容器+内层元素都带@type): mock com.demo.AnimalService save -j '{\"@type\":\"com.demo.GenericListResult\",\"items\":[{\"@type\":\"com.demo.Dog\",\"name\":\"arthas\"}]}' -b '#json.items[0].name=\"patched\",#this.params[0]=#json,#this.skip=false'\n")
public class MockCommand extends AnnotatedCommand {

    private static final Logger logger = LoggerFactory.getLogger(MockCommand.class);

    private String classPattern;
    private String methodPattern;
    private String beforeOgnl;
    private String afterOgnl;
    private String jsonPayload;
    private boolean isException = false;
    private boolean clear = false;
    private boolean clearAll = false;
    private boolean list = false;
    private Integer sizeLimit = 10 * 1024 * 1024;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;
    private boolean verbose = false;

    private String hashCode;
    private String classLoaderClass;
    private static final List<Class<?>> mockClass = new ArrayList<>();
    private static final AtomicBoolean APPENDED_TO_SYSTEM_CLASSLOADER = new AtomicBoolean(false);
    private static final String SPRING_CGLIB_MARKER = "$$EnhancerBySpringCGLIB$$";
    private static final String SPRING_CGLIB_MARKER_ALT = "$$SpringCGLIB$$";
    private static final String CGLIB_MARKER = "$$EnhancerByCGLIB$$";
    private static final String STRICT_DISABLE_HINT = "mock requires `options strict false` before installation when using OGNL/JSON; current strict=true may install successfully but fail at runtime.";

    @Argument(index = 0, argName = "class-pattern", required = false)
    @Description("The full qualified class name you want to mock")
    public void setClassPattern(String classPattern) {
        this.classPattern = StringUtils.normalizeClassName(classPattern);
    }

    @Argument(index = 1, argName = "method-pattern", required = false)
    @Description("The method name you want to mock")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    @Option(shortName = "b", longName = "beforeOgnl")
    @Description("Mock before method invocation (修改入参/立即返回)")
    public void setBeforeOgnl(String beforeOgnl) {
        this.beforeOgnl = beforeOgnl;
    }

    @Option(shortName = "a", longName = "afterOgnl")
    @Description("Mock after method invocation (修改入参/立即返回)")
    public void setAfterOgnl(String afterOgnl) {
        this.afterOgnl = afterOgnl;
    }

    @Option(shortName = "j", longName = "json")
    @Description("JSON payload bound to #this.json before OGNL execution")
    public void setJsonPayload(String jsonPayload) {
        this.jsonPayload = jsonPayload;
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

    @Option(longName = "list", flag = true)
    @Description("List active mocks")
    public void setList(boolean list) {
        this.list = list;
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

    public String getJsonPayload() {
        return jsonPayload;
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

    @Override
    public void complete(Completion completion) {
        int argumentIndex = CompletionUtils.detectArgumentIndex(completion);
        if (argumentIndex == 1) {
            if (!CompletionUtils.completeClassName(completion)) {
                super.complete(completion);
            }
            return;
        }
        if (argumentIndex == 2) {
            if (!CompletionUtils.completeMethodName(completion)) {
                super.complete(completion);
            }
            return;
        }
        super.complete(completion);
    }

    @Override
    public void process(CommandProcess process) {
        Session session = process.session();
        if (session == null) {
            process.end(-1, "session unavailable.");
            return;
        }

        if (!session.tryLock()) {
            String msg = "someone else is enhancing classes, pls. wait.";
            process.appendResult(EnhancerModelFactory.create(null, false, msg));
            process.end(-1, msg);
            return;
        }
        int lock = session.getLock();
        try {
            Instrumentation inst = session.getInstrumentation();
            Class<?> runtimeAdviceClass = ensureAdviceClassesVisible(inst);

            if (list) {
                process.end(0, formatMockList(describeRuntimeMocks(runtimeAdviceClass)));
                return;
            }

            // 处理清除逻辑
            if (clearAll) {
                clearAllMocks(inst);
                process.appendResult(EnhancerModelFactory.create(new EnhancerAffect(), true, "All mocks cleared."));
                process.end(0, "OK");
                return;
            }

            if (clear) {
                if (classPattern == null || methodPattern == null) {
                    process.end(-1, "--clear requires class-pattern and method-pattern");
                    return;
                }
                clearMock(inst);
                process.appendResult(EnhancerModelFactory.create(new EnhancerAffect(), true, "Mock cleared."));
                process.end(0, "OK");
                return;
            }

            // 检查必要参数
            if (classPattern == null || methodPattern == null) {
                process.end(-1, "class-pattern and method-pattern are required.");
                return;
            }

            if (shouldRejectInstallUnderStrictMode()) {
                process.end(-1, STRICT_DISABLE_HINT);
                return;
            }

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
            Map<Class<?>, Set<String>> methodsByTargetClass = new LinkedHashMap<>();

            for (Class<?> clazz : matchingClasses) {
                Class<?> targetClass = resolveEnhanceableClass(clazz);
                Set<String> matchedMethods = findMatchedMethods(targetClass, methodNameMatcher);
                if (matchedMethods.isEmpty()) {
                    continue;
                }
                methodsByTargetClass.computeIfAbsent(targetClass, key -> new LinkedHashSet<>()).addAll(matchedMethods);
            }

            if (methodsByTargetClass.isEmpty()) {
                process.end(-1, "No method matched: " + methodPattern);
                return;
            }

            for (Map.Entry<Class<?>, Set<String>> entry : methodsByTargetClass.entrySet()) {
                Class<?> targetClass = entry.getKey();
                Set<String> matchedMethods = entry.getValue();
                putRuntimeMocks(runtimeAdviceClass, targetClass, matchedMethods, this);
                Set<String> allMockedMethods = getRuntimeMockedMethods(runtimeAdviceClass, targetClass);
                byte[] enhancedBytes = AsmMockEnhancer.enhance(targetClass, allMockedMethods);

                if (verbose) {
                    logger.info("Enhanced class {} methods {}:\n{}", targetClass.getName(),
                            allMockedMethods, Decompiler.decompile(enhancedBytes));
                }
                if (!mockClass.contains(targetClass)) {
                    mockClass.add(targetClass);
                }
                entries.add(new RetransformEntry(targetClass.getName(),
                        enhancedBytes,
                        hashCode, classLoaderClass));
            }

            // 注册到 Arthas 全局 retransform 管理器
            Method method = ReflectUtils.findMethod(
                    "com.taobao.arthas.core.command.klass100.RetransformCommand.initTransformer()");
            method.setAccessible(true);
            method.invoke(null);
            RetransformCommand.addRetransformEntry(entries);
            inst.retransformClasses(methodsByTargetClass.keySet().toArray(new Class[0]));

            process.appendResult(EnhancerModelFactory.create(affect, true, "Mock installed."));
            process.end(0, "OK");
        } catch (Throwable e) {
            logger.warn("mock failed.", e);
            process.end(-1, "mock failed, beforeOgnl is: " + this.getBeforeOgnl() + ", afterOgnl is: "
                    + this.getAfterOgnl() + ", " + e.getMessage() + ", visit " + LogUtil.loggingFile()
                    + " for more details.");
        } finally {
            if (session.getLock() == lock) {
                session.unLock();
            }
        }

    }

    @SuppressWarnings("unchecked")
    private static List<String> describeRuntimeMocks(Class<?> runtimeAdviceClass) throws ReflectiveOperationException {
        if (runtimeAdviceClass == OgnlMockAdvice.class) {
            return OgnlMockAdvice.describeMocks();
        }
        Method method = runtimeAdviceClass.getMethod("describeMocks");
        return (List<String>) method.invoke(null);
    }

    private static String formatMockList(List<String> mockLines) {
        if (mockLines == null || mockLines.isEmpty()) {
            return "No active mocks.";
        }
        return "Active mocks:\n" + String.join("\n", mockLines);
    }

    private static Class<?> resolveEnhanceableClass(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        String className = clazz.getName();
        if (!className.contains(SPRING_CGLIB_MARKER)
                && !className.contains(SPRING_CGLIB_MARKER_ALT)
                && !className.contains(CGLIB_MARKER)) {
            return clazz;
        }
        Class<?> superClass = clazz.getSuperclass();
        return superClass != null && superClass != Object.class ? superClass : clazz;
    }

    private static Class<?> ensureAdviceClassesVisible(Instrumentation instrumentation) {
        try {
            if (APPENDED_TO_SYSTEM_CLASSLOADER.compareAndSet(false, true)) {
                CodeSource codeSource = MockCommand.class.getProtectionDomain().getCodeSource();
                if (codeSource == null) {
                    return OgnlMockAdvice.class;
                }
                File currentJar = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                if (currentJar.isFile()) {
                    instrumentation.appendToSystemClassLoaderSearch(new JarFile(currentJar));
                }
            }
            try {
                return Class.forName(OgnlMockAdvice.class.getName(), true, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                return OgnlMockAdvice.class;
            }
        } catch (Exception e) {
            logger.warn("Failed to append mock command jar to system classloader search.", e);
            APPENDED_TO_SYSTEM_CLASSLOADER.set(false);
            return OgnlMockAdvice.class;
        }
    }

    private static void putRuntimeMocks(Class<?> runtimeAdviceClass, Class<?> targetClass, Set<String> matchedMethods,
            MockCommand mockCommand) throws ReflectiveOperationException {
        for (String methodName : matchedMethods) {
            if (runtimeAdviceClass == OgnlMockAdvice.class) {
                OgnlMockAdvice.putMockConfig(targetClass, methodName, mockCommand.getBeforeOgnl(),
                        mockCommand.getAfterOgnl(), mockCommand.getJsonPayload(), GlobalOptions.strict);
                continue;
            }
            Method method = runtimeAdviceClass.getMethod("putMockConfig", Class.class, String.class, String.class,
                    String.class, String.class, boolean.class);
            method.invoke(null, targetClass, methodName, mockCommand.getBeforeOgnl(), mockCommand.getAfterOgnl(),
                    mockCommand.getJsonPayload(), GlobalOptions.strict);
        }
    }

    private static void removeRuntimeMock(Class<?> runtimeAdviceClass, Class<?> targetClass)
            throws ReflectiveOperationException {
        if (runtimeAdviceClass == OgnlMockAdvice.class) {
            OgnlMockAdvice.removeMock(targetClass);
            return;
        }
        Method method = runtimeAdviceClass.getMethod("removeMock", Class.class);
        method.invoke(null, targetClass);
    }

    private static Set<String> getRuntimeMockedMethods(Class<?> runtimeAdviceClass, Class<?> targetClass)
            throws ReflectiveOperationException {
        String[] mockedMethods;
        if (runtimeAdviceClass == OgnlMockAdvice.class) {
            mockedMethods = OgnlMockAdvice.getMockedMethods(targetClass);
        } else {
            Method method = runtimeAdviceClass.getMethod("getMockedMethods", Class.class);
            mockedMethods = (String[]) method.invoke(null, targetClass);
        }
        Set<String> methodNames = new LinkedHashSet<>();
        Collections.addAll(methodNames, mockedMethods);
        return methodNames;
    }

    private static Set<String> findMatchedMethods(Class<?> clazz, Matcher<String> methodNameMatcher) {
        Set<String> matchedMethods = new LinkedHashSet<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.isBridge() && !method.isSynthetic() && methodNameMatcher.matching(method.getName())) {
                matchedMethods.add(method.getName());
            }
        }
        return matchedMethods;
    }

    private boolean shouldRejectInstallUnderStrictMode() {
        if (!GlobalOptions.strict) {
            return false;
        }
        return !StringUtils.isBlank(beforeOgnl)
                || !StringUtils.isBlank(afterOgnl)
                || !StringUtils.isBlank(jsonPayload);
    }

    private void clearMock(Instrumentation inst) {
        Set<Class<?>> classes = SearchUtils.searchClass(inst, SearchUtils.classNameMatcher(classPattern, isRegEx));
        Class<?> runtimeAdviceClass = ensureAdviceClassesVisible(inst);
        for (Class<?> clazz : classes) {
            restoreMockedClass(inst, runtimeAdviceClass, clazz, "clear");
            mockClass.remove(clazz);
        }
    }

    private void clearAllMocks(Instrumentation inst) {
        Class<?> runtimeAdviceClass = ensureAdviceClassesVisible(inst);
        for (Class<?> className : new ArrayList<>(mockClass)) {
            Set<Class<?>> classes = SearchUtils.searchClass(inst,
                    SearchUtils.classNameMatcher(className.getName(), false));
            for (Class<?> clazz : classes) {
                restoreMockedClass(inst, runtimeAdviceClass, clazz, "clear-all");
            }
            mockClass.remove(className);
        }
    }

    private void restoreMockedClass(Instrumentation inst, Class<?> runtimeAdviceClass, Class<?> clazz, String action) {
        try {
            inst.retransformClasses(clazz);
        } catch (Exception e) {
            logger.warn("Failed to retransform class on {}: {}", action, clazz.getName(), e);
        }
        try {
            removeRuntimeMock(runtimeAdviceClass, clazz);
        } catch (ReflectiveOperationException e) {
            logger.warn("Failed to remove runtime mock for class on {}: {}", action, clazz.getName(), e);
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

        private OgnlMockAdvice() {
        }

        private static final String OGNL_STRICT_FIELD_NAME = "_useStricterInvocation";
        private static final JSONReader.Feature[] AUTO_TYPE_FEATURES = new JSONReader.Feature[] {
                JSONReader.Feature.SupportAutoType
        };

        static Map<Class<?>, Map<String, MockConfig>> mockCommands = new ConcurrentHashMap<>();

        public static void putMockConfig(Class<?> clz, String methodPattern, String beforeOgnl, String afterOgnl,
                String jsonPayload, boolean strict) {
            mockCommands.computeIfAbsent(clz, key -> new ConcurrentHashMap<>())
                    .put(methodPattern, new MockConfig(beforeOgnl, afterOgnl, jsonPayload, strict));
        }

        public static void removeMock(Class<?> clz) {
            mockCommands.remove(clz);
        }

        public static String[] getMockedMethods(Class<?> clz) {
            Map<String, MockConfig> methodMocks = mockCommands.get(clz);
            if (methodMocks == null || methodMocks.isEmpty()) {
                return new String[0];
            }
            return methodMocks.keySet().toArray(new String[0]);
        }

        public static List<String> describeMocks() {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<Class<?>, Map<String, MockConfig>> classEntry : mockCommands.entrySet()) {
                String className = classEntry.getKey().getName();
                for (Map.Entry<String, MockConfig> methodEntry : classEntry.getValue().entrySet()) {
                    MockConfig config = methodEntry.getValue();
                    lines.add(className + "#" + methodEntry.getKey()
                            + " [before=" + config.getBeforeOgnl()
                            + ", after=" + config.getAfterOgnl()
                            + ", json=" + config.getJsonPayload()
                            + ", strict=" + config.isStrict() + "]");
                }
            }
            Collections.sort(lines);
            return lines;
        }

        public static boolean isSkipped(OgnlContext ognlContext) {
            return ognlContext != null && Boolean.TRUE.equals(ognlContext.skip);
        }

        public static Completion complete(OgnlContext ognlContext, Class<?> clazz, Object returned, Throwable thrown) {
            if (ognlContext == null) {
                return new Completion(returned, thrown);
            }

            ognlContext.setOriginReturnObj(returned);
            if (!Boolean.TRUE.equals(ognlContext.skip)) {
                ognlContext.setReturnObj(returned);
            }
            ognlContext.setThrowExp(thrown);

            OgnlMockAdvice.invoke(ognlContext, clazz, null, null, true);

            Object finalReturn = returned;
            Throwable finalThrowable = thrown;
            if (!Optional.empty().equals(ognlContext.returnObj)) {
                finalReturn = ognlContext.returnObj;
                finalThrowable = null;
            }
            if (ognlContext.getThrowExp() != null) {
                finalThrowable = ognlContext.getThrowExp();
            }
            return new Completion(finalReturn, finalThrowable);
        }

        public static OgnlContext invoke(Object target,
                Class<?> clazz,
                String methodName,
                Object[] args,
                boolean isAfter) {
            MockConfig mockConfig = getMockCommand(clazz, methodName);
            if (isAfter && target instanceof OgnlContext) {
                OgnlContext existingContext = (OgnlContext) target;
                if (clazz == null) {
                    clazz = existingContext.getClazz();
                }
                if (methodName == null && existingContext.getMethod() != null) {
                    methodName = existingContext.getMethod().getName();
                }
                mockConfig = getMockCommand(clazz, methodName);
            }
            if (mockConfig == null) {
                return null;
            }

            OgnlContext ognlContext;
            String express;
            if (isAfter) {
                ognlContext = (OgnlContext) target;
                if (mockConfig.getAfterOgnl() == null) {
                    return ognlContext;
                }
                express = mockConfig.getAfterOgnl();
            } else {
                ognlContext = OgnlContext.init(clazz.getClassLoader(), clazz, resolveArthasMethod(clazz, methodName),
                        target, args, null);
                if (mockConfig.getBeforeOgnl() == null) {
                    return ognlContext;
                }
                express = mockConfig.getBeforeOgnl();
            }

            express = normalizeJsonAlias(express);
            try {
                bindJsonArgumentIfNecessary(ognlContext, clazz, methodName, mockConfig, isAfter);
                getExpressionResult(express, ognlContext, mockConfig.isStrict());
            } catch (Throwable e) {
                throw MockCommand.propagateMockException(e);
            }

            return ognlContext;
        }

        private static void bindJsonArgumentIfNecessary(OgnlContext ognlContext,
                Class<?> clazz,
                String methodName,
                MockConfig mockConfig,
                boolean isAfter) {
            if (ognlContext == null || mockConfig == null || mockConfig.getJsonPayload() == null
                    || mockConfig.getJsonPayload().trim().isEmpty()) {
                return;
            }
            Method method = resolveReflectiveMethod(clazz, methodName, ognlContext.getParams());
            ognlContext.setJson(resolveTypedJsonValue(mockConfig.getJsonPayload(), method, ognlContext.getParams(), isAfter));
        }

        private static Object resolveTypedJsonValue(String jsonPayload, Method method, Object[] params, boolean isAfter) {
            Object parsedJson = parseJsonPayload(jsonPayload);
            if (isAfter) {
                return convertJsonValue(parsedJson, method.getGenericReturnType());
            }
            if (params == null || params.length == 0) {
                return parsedJson;
            }
            Type[] parameterTypes = method.getGenericParameterTypes();
            if (params.length == 1) {
                return convertJsonValue(parsedJson, parameterTypes[0]);
            }
            if (parsedJson instanceof List) {
                List<?> jsonArray = (List<?>) parsedJson;
                Object[] converted = new Object[params.length];
                for (int i = 0; i < params.length && i < jsonArray.size(); i++) {
                    converted[i] = convertJsonValue(jsonArray.get(i), parameterTypes[i]);
                }
                return converted;
            }
            return parsedJson;
        }

        private static Object getExpressionResult(String express, OgnlContext ognlContext, boolean strict)
                throws ExpressException {
            StrictState strictState = syncStrictState(strict);
            try {
                return ExpressFactory.threadLocalExpress(ognlContext).get(express);
            } finally {
                restoreStrictState(strictState);
            }
        }

        private static StrictState syncStrictState(boolean strict) {
            boolean previousGlobalStrict = GlobalOptions.strict;
            boolean previousOgnlStrict = previousGlobalStrict;
            boolean ognlFieldAccessible = false;
            try {
                Field field = OgnlRuntime.class.getDeclaredField(OGNL_STRICT_FIELD_NAME);
                field.setAccessible(true);
                previousOgnlStrict = field.getBoolean(null);
                ognlFieldAccessible = true;
                if (previousOgnlStrict != strict) {
                    updateStrictField(field, strict);
                }
            } catch (ReflectiveOperationException e) {
                logger.debug("Failed to sync OGNL strict invocation for mock command.", e);
            }
            if (previousGlobalStrict != strict) {
                GlobalOptions.strict = strict;
            }
            return new StrictState(previousGlobalStrict, previousOgnlStrict, ognlFieldAccessible);
        }

        private static void restoreStrictState(StrictState strictState) {
            if (strictState == null) {
                return;
            }
            if (GlobalOptions.strict != strictState.previousGlobalStrict) {
                GlobalOptions.strict = strictState.previousGlobalStrict;
            }
            if (!strictState.ognlFieldAccessible) {
                return;
            }
            try {
                Field field = OgnlRuntime.class.getDeclaredField(OGNL_STRICT_FIELD_NAME);
                field.setAccessible(true);
                boolean current = field.getBoolean(null);
                if (current != strictState.previousOgnlStrict) {
                    updateStrictField(field, strictState.previousOgnlStrict);
                }
            } catch (ReflectiveOperationException e) {
                logger.debug("Failed to restore OGNL strict invocation for mock command.", e);
            }
        }

        private static String normalizeJsonAlias(String express) {
            if (express == null || !express.contains("#json")) {
                return express;
            }
            StringBuilder builder = new StringBuilder(express.length() + 16);
            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            for (int i = 0; i < express.length(); i++) {
                char ch = express.charAt(i);
                if (ch == '\'' && !inDoubleQuote && !isEscaped(express, i)) {
                    inSingleQuote = !inSingleQuote;
                } else if (ch == '"' && !inSingleQuote && !isEscaped(express, i)) {
                    inDoubleQuote = !inDoubleQuote;
                }
                if (!inSingleQuote && !inDoubleQuote && startsWithJsonAlias(express, i)) {
                    builder.append("#this.json");
                    i += 4;
                    continue;
                }
                builder.append(ch);
            }
            return builder.toString();
        }

        private static boolean startsWithJsonAlias(String express, int index) {
            if (!express.startsWith("#json", index)) {
                return false;
            }
            int nextIndex = index + 5;
            if (nextIndex >= express.length()) {
                return true;
            }
            char next = express.charAt(nextIndex);
            return !Character.isLetterOrDigit(next) && next != '_' && next != '$';
        }

        private static boolean isEscaped(String text, int index) {
            int backslashCount = 0;
            for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
                backslashCount++;
            }
            return backslashCount % 2 != 0;
        }

        private static Object convertJsonValue(Object parsedJson, Type targetType) {
            if (parsedJson == null) {
                return null;
            }
            if (targetType instanceof Class && ((Class<?>) targetType).isInstance(parsedJson)) {
                return parsedJson;
            }
            return JSON.parseObject(JSON.toJSONString(parsedJson), targetType, AUTO_TYPE_FEATURES);
        }

        private static Object parseJsonPayload(String express) {
            return JSON.parse(express.trim(), AUTO_TYPE_FEATURES);
        }

        private static Method resolveReflectiveMethod(Class<?> clazz, String methodName, Object[] args) {
            if (clazz == null || methodName == null) {
                throw new IllegalArgumentException("Unable to resolve reflective method for JSON payload binding");
            }
            int parameterCount = args == null ? -1 : args.length;
            List<Method> candidates = new ArrayList<>();
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.isBridge()
                        && !method.isSynthetic()
                        && method.getName().equals(methodName)
                        && (parameterCount < 0 || method.getParameterTypes().length == parameterCount)) {
                    candidates.add(method);
                }
            }
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("No reflective method named '" + methodName + "' found on "
                        + clazz.getName());
            }
            if (candidates.size() > 1) {
                throw new IllegalArgumentException("JSON payload binding does not support overloaded method: "
                        + clazz.getName() + "#" + methodName + ", please use explicit OGNL instead");
            }
            return candidates.get(0);
        }


        private static void updateStrictField(Field field, boolean value) throws ReflectiveOperationException {
            try {
                field.setBoolean(null, value);
            } catch (IllegalAccessException e) {
                updateStrictFieldWithUnsafe(field, value);
            }
        }

        private static void updateStrictFieldWithUnsafe(Field field, boolean value) throws ReflectiveOperationException {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Object staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
            long staticFieldOffset = ((Number) unsafeClass.getMethod("staticFieldOffset", Field.class)
                    .invoke(unsafe, field)).longValue();
            unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class)
                    .invoke(unsafe, staticFieldBase, staticFieldOffset, value);
        }

        private static MockConfig getMockCommand(Class<?> clazz, String methodName) {
            if (clazz == null || methodName == null) {
                return null;
            }
            Map<String, MockConfig> methodMocks = mockCommands.get(clazz);
            if (methodMocks == null) {
                return null;
            }
            return methodMocks.get(methodName);
        }


        private static final class MockConfig {
            private final String beforeOgnl;
            private final String afterOgnl;
            private final String jsonPayload;
            private final boolean strict;

            private MockConfig(String beforeOgnl, String afterOgnl, String jsonPayload, boolean strict) {
                this.beforeOgnl = beforeOgnl;
                this.afterOgnl = afterOgnl;
                this.jsonPayload = jsonPayload;
                this.strict = strict;
            }

            private String getBeforeOgnl() {
                return beforeOgnl;
            }

            private String getAfterOgnl() {
                return afterOgnl;
            }

            private String getJsonPayload() {
                return jsonPayload;
            }

            private boolean isStrict() {
                return strict;
            }
        }

        private static final class StrictState {
            private final boolean previousGlobalStrict;
            private final boolean previousOgnlStrict;
            private final boolean ognlFieldAccessible;

            private StrictState(boolean previousGlobalStrict, boolean previousOgnlStrict, boolean ognlFieldAccessible) {
                this.previousGlobalStrict = previousGlobalStrict;
                this.previousOgnlStrict = previousOgnlStrict;
                this.ognlFieldAccessible = ognlFieldAccessible;
            }
        }

        public static final class Completion {
            private final Object returnValue;
            private final Throwable throwable;

            private Completion(Object returnValue, Throwable throwable) {
                this.returnValue = returnValue;
                this.throwable = throwable;
            }

            public Object getReturnValue() {
                return returnValue;
            }

            public Throwable getThrowable() {
                return throwable;
            }
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

    }
}
