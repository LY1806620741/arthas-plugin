package io.github.ly1806620741.arthas.plugin;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.CommandLine;
import com.taobao.middleware.cli.annotations.CLIConfigurator;

public class MockCommandTest {

    private static CLI cli = null;

    @BeforeAll
    public static void before() {
        cli = CLIConfigurator.define(MockCommand.class);
    }

    @Test
    void testProcess() {

        List<String> args = Arrays.asList("class", "method");
        MockCommand mockCommand = new MockCommand();
        CommandLine commandLine = cli.parse(args, true);
        try {
            CLIConfigurator.inject(commandLine, mockCommand);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        Assertions.assertEquals(mockCommand.getClassPattern(), "class");
    }
}
