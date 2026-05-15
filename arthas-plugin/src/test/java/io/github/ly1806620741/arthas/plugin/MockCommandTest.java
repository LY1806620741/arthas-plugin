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

    private static CglibProxyTarget createCglibProxy() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(CglibProxyTarget.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> proxy.invokeSuper(obj, args));
        return (CglibProxyTarget) enhancer.create();
    }
}
