package io.github.ly1806620741.arthas.plugin;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.taobao.arthas.core.GlobalOptions;
import com.taobao.arthas.core.server.ArthasBootstrap;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.session.Session;

import demo.MathGame;
import net.bytebuddy.agent.ByteBuddyAgent;

public class MockCommandTest {

    @Test
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
    void testPropagateMockExceptionReturnsRuntimeCauseDirectly() {
        RuntimeException cause = new IllegalStateException("boom");
        Throwable throwable = new Exception("wrapper", cause);

        RuntimeException runtimeException = Assertions.assertThrows(RuntimeException.class,
                () -> MockCommand.propagateMockException(throwable));
        Assertions.assertSame(cause, runtimeException);
    }

    @Test
    void testPropagateMockExceptionWrapsCheckedCause() {
        Throwable checkedCause = new Throwable("checked");
        Throwable throwable = new Exception("wrapper", checkedCause);

        RuntimeException runtimeException = MockCommand.propagateMockException(throwable);
        Assertions.assertSame(throwable, runtimeException.getCause());
    }

    @Test
    void testPropagateMockExceptionWrapsThrowableWithoutCause() {
        Throwable throwable = new Exception("wrapper-without-cause");

        RuntimeException runtimeException = MockCommand.propagateMockException(throwable);
        Assertions.assertSame(throwable, runtimeException.getCause());
    }

    @Test
    void testPropagateMockExceptionWrapsErrorCause() {
        Error errorCause = new AssertionError("boom");
        Throwable throwable = new Exception("wrapper", errorCause);

        RuntimeException runtimeException = MockCommand.propagateMockException(throwable);
        Assertions.assertSame(throwable, runtimeException.getCause());
    }

    @Test
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
}
