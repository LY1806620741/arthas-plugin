package io.github.ly1806620741.arthas.plugin;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.taobao.arthas.core.GlobalOptions;
import com.taobao.arthas.core.server.ArthasBootstrap;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.CommandLine;
import com.taobao.middleware.cli.annotations.CLIConfigurator;

import demo.MathGame;
import net.bytebuddy.agent.ByteBuddyAgent;

public class MockCommandTest {

    private static CLI cli = null;

    @BeforeAll
    public static void before() {
        cli = CLIConfigurator.define(MockCommand.class);
    }

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
            MockCommand mockCommand = buildMockCommand("demo.MathGame", "primeFactors", "-b", "#this.returnObj=null");
            Assertions.assertEquals(mockCommand.getClassPattern(), "demo.MathGame");
            { // before mock null
                mockCommand.process(commandProcess);
                Assertions.assertNull(game.primeFactors(0));
                // list mock
                // buildMockCommand("demo.MathGame", "primeFactors", "-l").process(commandProcess)
            }
            { // before mock null
                // mockCommand = buildMockCommand("demo.MathGame", "primeFactors","#this.returnObj=null");
                // mockCommand.process(commandProcess);
                // Assertions.assertNull(game.primeFactors(0));
            }

        }

        // {
        // // after mock null
        // args = Arrays.asList("demo.MathGame", "primeFactors", "-a",
        // "@java.lang.System.out.println('after')");
        // mockCommand.process(commandProcess);
        // Assertions.assertNull(game.primeFactors(0));
        // }

    }

    private MockCommand buildMockCommand(String... strings) {
        MockCommand mockCommand = new MockCommand();
        List<String> args = Arrays.asList(strings);
        CommandLine commandLine = cli.parse(args, true);
        try {
            CLIConfigurator.inject(commandLine, mockCommand);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        return mockCommand;
    }
}
