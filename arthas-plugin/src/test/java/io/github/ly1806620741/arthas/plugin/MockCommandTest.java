package io.github.ly1806620741.arthas.plugin;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;
import org.mockito.Mockito;

import com.taobao.arthas.core.server.ArthasBootstrap;
import com.taobao.arthas.core.shell.Shell;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.shell.system.Job;
import com.taobao.arthas.core.util.reflect.FieldUtils;
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

        // new AnnotatedCommandImpl

        List<String> args = Arrays.asList("demo.MathGame primeFactors 'return null'".split(" "));
        MockCommand mockCommand = new MockCommand();
        CommandLine commandLine = cli.parse(args, true);
        try {
            CLIConfigurator.inject(commandLine, mockCommand);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        Assertions.assertEquals(mockCommand.getClassPattern(), "demo.MathGame");

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
        ArthasBootstrap instance = ArthasBootstrap.getInstance(instrumentation, "ip=127.0.0.1");

        {
            Object a = MathGame.class;
            CommandProcess commandProcess = Mockito.mock(CommandProcess.class);
            Session session = Mockito.mock(Session.class);
            Mockito.doReturn(session).when(commandProcess).session();
            Mockito.doReturn(true).when(session).tryLock();
            Mockito.doReturn(instrumentation).when(session).getInstrumentation();
            mockCommand.process(commandProcess);
        }

    }
}
