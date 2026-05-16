package io.github.ly1806620741.arthas.plugin;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.util.jar.JarFile;

import com.taobao.arthas.core.GlobalOptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;

import com.taobao.arthas.core.server.ArthasBootstrap;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.session.Session;

import demo.MathGame;
import net.bytebuddy.agent.ByteBuddyAgent;

public class MockCommandTest {

    @Test
    @DisplayName("测试MockCommand能够正确mock方法并且不抛出异常")
    void testProcess() throws Throwable {

        Instrumentation instrumentation = ByteBuddyAgent.install();

        {
            // 测试前工作 重定位spyjar
            CodeSource codeSource = ArthasBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                File arthasCoreJarFile = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                File spyJar = new File(arthasCoreJarFile.getAbsolutePath().replaceAll("arthas-core", "arthas-spy"));
                Assertions.assertTrue(spyJar.exists());
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(spyJar));
            }
        }

        {
            // 测试前工作 初始化arthas
            ArthasBootstrap instance = ArthasBootstrap.getInstance(instrumentation, "ip=127.0.0.1");
            GlobalOptions.strict = false;
        }

        CommandProcess commandProcess;
        {
            // 测试前工作 mock session
            commandProcess = Mockito.mock(CommandProcess.class);
            Session session = Mockito.mock(Session.class);
            Mockito.doReturn(session).when(commandProcess).session();
            Mockito.doReturn(true).when(session).tryLock();
            Mockito.doReturn(instrumentation).when(session).getInstrumentation();

        }

        MathGame game = new MathGame();

        {
            // 原本的异常
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                game.primeFactors(0);
            });
        }

        { // 中断原逻辑测试
            MockCommand mockCommand = buildMockCommand("demo.MathGame", "primeFactors", "#this.returnObj=null");
            Assertions.assertEquals(mockCommand.getClassPattern(), "demo.MathGame");
            { // before mock null
                mockCommand.process(commandProcess);
                Assertions.assertNull(game.primeFactors(0));
            }
        }
    }

    @Test
    @DisplayName("AI:测试propagateMockException能够正确处理不同类型的异常")
    void testPropagateMockExceptionReturnsRuntimeCauseDirectly() {
        RuntimeException cause = new IllegalStateException("boom");
        Throwable throwable = new Exception("wrapper", cause);

        RuntimeException runtimeException = Assertions.assertThrows(RuntimeException.class,
                () -> MockCommand.propagateMockException(throwable));
        Assertions.assertSame(cause, runtimeException);
    }

    @Test
    @DisplayName("AI:测试propagateMockException能够正确处理checked异常")
    void testPropagateMockExceptionWrapsCheckedCause() {
        Throwable checkedCause = new Throwable("checked");
        Throwable throwable = new Exception("wrapper", checkedCause);

        RuntimeException runtimeException = MockCommand.propagateMockException(throwable);
        Assertions.assertSame(throwable, runtimeException.getCause());
    }

    @Test
    @DisplayName("AI:测试propagateMockException能够正确处理没有cause的异常")
    void testPropagateMockExceptionWrapsThrowableWithoutCause() {
        Throwable throwable = new Exception("wrapper-without-cause");

        RuntimeException runtimeException = MockCommand.propagateMockException(throwable);
        Assertions.assertSame(throwable, runtimeException.getCause());
    }

    @Test
    @DisplayName("AI:测试propagateMockException能够正确处理Error类型的cause,不是为什么搞那么多发散的case")
    void testPropagateMockExceptionWrapsErrorCause() {
        Error errorCause = new AssertionError("boom");
        Throwable throwable = new Exception("wrapper", errorCause);

        RuntimeException runtimeException = MockCommand.propagateMockException(throwable);
        Assertions.assertSame(throwable, runtimeException.getCause());
    }

    @Test
    @DisplayName("测试afterOgnl能够覆盖方法返回值")
    void testAfterOgnlCanOverrideReturnValue() throws Throwable {

        Instrumentation instrumentation = ByteBuddyAgent.install();

        {
            CodeSource codeSource = ArthasBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                File arthasCoreJarFile = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                File spyJar = new File(arthasCoreJarFile.getAbsolutePath().replaceAll("arthas-core", "arthas-spy"));
                Assertions.assertTrue(spyJar.exists());
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(spyJar));
            }
        }

        {
            ArthasBootstrap instance = ArthasBootstrap.getInstance(instrumentation, "ip=127.0.0.1");
            GlobalOptions.strict = false;
        }

        CommandProcess commandProcess;
        {
            commandProcess = Mockito.mock(CommandProcess.class);
            Session session = Mockito.mock(Session.class);
            Mockito.doReturn(session).when(commandProcess).session();
            Mockito.doReturn(true).when(session).tryLock();
            Mockito.doReturn(instrumentation).when(session).getInstrumentation();
        }

        MathGame game = new MathGame();
        MockCommand mockCommand = buildAfterMockCommand("demo.MathGame", "primeFactors", "#this.returnObj=null");

        mockCommand.process(commandProcess);
        Assertions.assertNull(game.primeFactors(12));
    }

    @Test
    @DisplayName("测试同一个类的不同方法互不影响")
    void testSameClassDifferentMethodsDoNotOverrideEachOther() throws Throwable {

        Instrumentation instrumentation = ByteBuddyAgent.install();

        {
            CodeSource codeSource = ArthasBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                File arthasCoreJarFile = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                File spyJar = new File(arthasCoreJarFile.getAbsolutePath().replaceAll("arthas-core", "arthas-spy"));
                Assertions.assertTrue(spyJar.exists());
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(spyJar));
            }
        }

        {
            ArthasBootstrap instance = ArthasBootstrap.getInstance(instrumentation, "ip=127.0.0.1");
            GlobalOptions.strict = false;
        }

        CommandProcess commandProcess;
        {
            commandProcess = Mockito.mock(CommandProcess.class);
            Session session = Mockito.mock(Session.class);
            Mockito.doReturn(session).when(commandProcess).session();
            Mockito.doReturn(true).when(session).tryLock();
            Mockito.doReturn(instrumentation).when(session).getInstrumentation();
        }

        DualMethodTarget target = new DualMethodTarget();

        buildMockCommand(DualMethodTarget.class.getName(), "first",
                "#this.returnObj=new java.lang.String('mock-first')")
                .process(commandProcess);
        buildMockCommand(DualMethodTarget.class.getName(), "second",
                "#this.returnObj=new java.lang.String('mock-second')")
                .process(commandProcess);

        Assertions.assertEquals("mock-first", target.first());
        Assertions.assertEquals("mock-second", target.second());
    }

    @Test
    @DisplayName("测试 --list 能查看当前 mock 列表")
    void testListMocks() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();

        buildAfterMockCommand(ListTarget.class.getName(), "name",
                "#this.returnObj=new java.lang.String('listed')")
                .process(mockCommandProcess(instrumentation));

        MockCommand listCommand = new MockCommand();
        listCommand.setList(true);
        CommandProcess listProcess = mockCommandProcess(instrumentation);

        listCommand.process(listProcess);

        Mockito.verify(listProcess).end(Mockito.eq(0), Mockito.contains(ListTarget.class.getName() + "#name"));
        Assertions.assertEquals("listed", new ListTarget().name());
    }

    @Test
    @DisplayName("测试 spring cglib 代理类名可以正常 mock")
    void testSpringCglibProxyClassCanBeMocked() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        CglibProxyTarget proxy = createCglibProxy();
        Assertions.assertTrue(proxy.getClass().getName().contains("CGLIB"), proxy.getClass().getName());

        MockCommand mockCommand = buildAfterMockCommand(proxy.getClass().getName(), "value",
                "#this.returnObj=new java.lang.String('mock-cglib')");

        mockCommand.process(commandProcess);

        Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
        Assertions.assertEquals("mock-cglib", proxy.value());
    }

    @Test
    @DisplayName("测试 spring cglib mock 会使用安装时捕获的 strict 配置")
    void testSpringCglibMockUsesCapturedStrictSettingAtRuntime() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        CglibProxyTarget proxy = createCglibProxy();
        MockCommand mockCommand = buildAfterMockCommand(proxy.getClass().getName(), "value",
                "#this.returnObj=new java.lang.String('mock-cglib-strict')");

        GlobalOptions.strict = false;
        mockCommand.process(commandProcess);

        GlobalOptions.strict = true;
        try {
            Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
            Assertions.assertEquals("mock-cglib-strict", proxy.value());
        } finally {
            GlobalOptions.strict = false;
        }
    }

    @Test
    @DisplayName("测试 -j 支持预载 JSON 数组并通过 OGNL 替换多个入参")
    void testJsonOptionArrayCanBeAssignedToMultipleParameters() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        JsonMultiArgumentTarget target = new JsonMultiArgumentTarget();
        MockCommand mockCommand = buildMockCommandWithJson(JsonMultiArgumentTarget.class.getName(), "join",
                "#this.params[0]=#json[0],#this.params[1]=#json[1],#this.skip=false",
                "[\"prefix\", {\"name\":\"multi\",\"child\":{\"city\":\"guangzhou\"}}]");

        mockCommand.process(commandProcess);

        Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
        String result = target.join("ignored", new JsonUser("origin", new JsonChild("beijing")));
        Assertions.assertEquals("prefix:multi@guangzhou", result);
    }

    @Test
    @DisplayName("测试 json 参数会先转对象并可在 afterOgnl 中修改后赋给 returnObj")
    void testJsonOptionCanBeMutatedAndAssignedToReturnObject() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        JsonReturnTarget target = new JsonReturnTarget();
        MockCommand mockCommand = buildAfterMockCommandWithJson(JsonReturnTarget.class.getName(), "load",
                "#this.json.child.city='nanjing',#this.returnObj=#this.json",
                "{\"name\":\"json-option\",\"child\":{\"city\":\"hangzhou\"}}");

        mockCommand.process(commandProcess);

        Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
        JsonUser user = target.load();
        Assertions.assertEquals("json-option", user.name);
        Assertions.assertNotNull(user.child);
        Assertions.assertEquals("nanjing", user.child.city);
    }

    @Test
    @DisplayName("测试 json 参数会先转对象并可在 beforeOgnl 中修改后赋给参数")
    void testJsonOptionCanBeMutatedAndAssignedToParameter() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        JsonArgumentTarget target = new JsonArgumentTarget();
        MockCommand mockCommand = buildMockCommandWithJson(JsonArgumentTarget.class.getName(), "describe",
                "#this.json.child.city='suzhou',#this.params[0]=#this.json,#this.skip=false",
                "{\"name\":\"json-before\",\"child\":{\"city\":\"shanghai\"}}");

        mockCommand.process(commandProcess);

        Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
        String result = target.describe(new JsonUser("origin", new JsonChild("beijing")));
        Assertions.assertEquals("json-before@suzhou", result);
    }

    @Test
    @DisplayName("测试 OGNL 中支持 #json 作为 #this.json 的别名")
    void testJsonAliasCanBeUsedInOgnl() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        JsonReturnTarget target = new JsonReturnTarget();
        MockCommand mockCommand = buildAfterMockCommandWithJson(JsonReturnTarget.class.getName(), "load",
                "#json.child.city='wuxi',#this.returnObj=#json",
                "{\"name\":\"json-alias\",\"child\":{\"city\":\"hangzhou\"}}");

        mockCommand.process(commandProcess);

        Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
        JsonUser user = target.load();
        Assertions.assertEquals("json-alias", user.name);
        Assertions.assertNotNull(user.child);
        Assertions.assertEquals("wuxi", user.child.city);
    }

    @Test
    @DisplayName("测试直接 OGNL 修改 json 后可直接赋给参数")
    void testJsonCanBeMutatedAndAssignedDirectlyToParameter() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        JsonArgumentTarget target = new JsonArgumentTarget();
        MockCommand mockCommand = buildMockCommandWithJson(JsonArgumentTarget.class.getName(), "describe",
                "#json.child.city='changsha',#this.params[0]=#json,#this.skip=false",
                "{\"name\":\"json-helper\",\"child\":{\"city\":\"shanghai\"}}");

        mockCommand.process(commandProcess);

        Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
        String result = target.describe(new JsonUser("origin", new JsonChild("beijing")));
        Assertions.assertEquals("json-helper@changsha", result);
    }


    @Test
    @DisplayName("测试 -j 绑定的 json 支持 @type 并可直接 OGNL 修改后赋给返回值")
    void testJsonOptionSupportsAutoTypeAndDirectOgnlMutation() throws Throwable {
        Instrumentation instrumentation = installInstrumentation();
        CommandProcess commandProcess = mockCommandProcess(instrumentation);

        JsonAutoTypeReturnTarget target = new JsonAutoTypeReturnTarget();
        MockCommand mockCommand = buildAfterMockCommandWithJson(JsonAutoTypeReturnTarget.class.getName(), "load",
                "#json.name='patched-dog',#this.returnObj=#json",
                autoTypeJson(JsonDog.class.getName(), "lucky", "corgi"));

        mockCommand.process(commandProcess);

        Mockito.verify(commandProcess).end(Mockito.eq(0), Mockito.eq("OK"));
        JsonAnimal animal = target.load();
        Assertions.assertInstanceOf(JsonDog.class, animal);
        Assertions.assertEquals("patched-dog", animal.name);
        Assertions.assertEquals("corgi", ((JsonDog) animal).breed);
    }

    private Instrumentation installInstrumentation() throws Throwable {
        Instrumentation instrumentation = ByteBuddyAgent.install();

        CodeSource codeSource = ArthasBootstrap.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            File arthasCoreJarFile = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
            File spyJar = new File(arthasCoreJarFile.getAbsolutePath().replaceAll("arthas-core", "arthas-spy"));
            Assertions.assertTrue(spyJar.exists());
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(spyJar));
        }

        ArthasBootstrap.getInstance(instrumentation, "ip=127.0.0.1");
        GlobalOptions.strict = false;
        return instrumentation;
    }

    private CommandProcess mockCommandProcess(Instrumentation instrumentation) {
        CommandProcess commandProcess = Mockito.mock(CommandProcess.class);
        Session session = Mockito.mock(Session.class);
        Mockito.doReturn(session).when(commandProcess).session();
        Mockito.doReturn(true).when(session).tryLock();
        Mockito.doReturn(0).when(session).getLock();
        Mockito.doReturn(instrumentation).when(session).getInstrumentation();
        return commandProcess;
    }

    private MockCommand buildMockCommand(String classPattern, String methodPattern, String beforeOgnl) {
        MockCommand mockCommand = new MockCommand();
        mockCommand.setClassPattern(classPattern);
        mockCommand.setMethodPattern(methodPattern);
        mockCommand.setBeforeOgnl(beforeOgnl);
        return mockCommand;
    }

    private MockCommand buildAfterMockCommand(String classPattern, String methodPattern, String afterOgnl) {
        MockCommand mockCommand = new MockCommand();
        mockCommand.setClassPattern(classPattern);
        mockCommand.setMethodPattern(methodPattern);
        mockCommand.setAfterOgnl(afterOgnl);
        return mockCommand;
    }

    private MockCommand buildMockCommandWithJson(String classPattern, String methodPattern, String beforeOgnl, String jsonPayload) {
        MockCommand mockCommand = buildMockCommand(classPattern, methodPattern, beforeOgnl);
        mockCommand.setJsonPayload(jsonPayload);
        return mockCommand;
    }

    private MockCommand buildAfterMockCommandWithJson(String classPattern, String methodPattern, String afterOgnl,
            String jsonPayload) {
        MockCommand mockCommand = buildAfterMockCommand(classPattern, methodPattern, afterOgnl);
        mockCommand.setJsonPayload(jsonPayload);
        return mockCommand;
    }

    static class DualMethodTarget {
        String first() {
            return "first";
        }

        String second() {
            return "second";
        }
    }

    static class ListTarget {
        String name() {
            return "name";
        }
    }

    public static class CglibProxyTarget {
        public String value() {
            return "origin";
        }
    }

    public static class JsonReturnTarget {
        public JsonUser load() {
            return new JsonUser("origin", new JsonChild("beijing"));
        }
    }

    public static class JsonArgumentTarget {
        public String describe(JsonUser user) {
            return user.name + "@" + user.child.city;
        }
    }

    public static class JsonMultiArgumentTarget {
        public String join(String prefix, JsonUser user) {
            return prefix + ":" + user.name + "@" + user.child.city;
        }
    }

    public static class JsonAutoTypeReturnTarget {
        public JsonAnimal load() {
            return new JsonAnimal("origin");
        }
    }

    public static class JsonAutoTypeArgumentTarget {
        public String describe(JsonAnimal animal) {
            if (animal instanceof JsonDog) {
                return animal.getClass().getSimpleName() + ":" + animal.name + ":" + ((JsonDog) animal).breed;
            }
            return animal.getClass().getSimpleName() + ":" + animal.name;
        }
    }

    public static class JsonAnimal {
        public String name;

        public JsonAnimal() {
        }

        public JsonAnimal(String name) {
            this.name = name;
        }
    }

    public static class JsonDog extends JsonAnimal {
        public String breed;

        public JsonDog() {
        }

        public JsonDog(String name, String breed) {
            super(name);
            this.breed = breed;
        }
    }

    public static class JsonUser {
        public String name;
        public JsonChild child;

        public JsonUser() {
        }

        public JsonUser(String name, JsonChild child) {
            this.name = name;
            this.child = child;
        }
    }

    public static class JsonChild {
        public String city;

        public JsonChild() {
        }

        public JsonChild(String city) {
            this.city = city;
        }
    }

    private String autoTypeJson(String typeName, String name, String breed) {
        return "{\"@type\":\"" + typeName + "\",\"name\":\"" + name + "\",\"breed\":\"" + breed + "\"}";
    }

    private static CglibProxyTarget createCglibProxy() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(CglibProxyTarget.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> proxy.invokeSuper(obj, args));
        return (CglibProxyTarget) enhancer.create();
    }
}
