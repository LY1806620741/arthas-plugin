package io.github.ly1806620741.arthas.plugin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class ArthasBootIntegrationIT {

    private static final Pattern MOCKED_OUTPUT_PATTERN = Pattern.compile("(?m)^-?\\d+=99991\\*99989\\s*$");

    @Test
    @DisplayName("使用 original 插件增强官方 arthas-core 后验证 math-game mock 功能")
    void artifactRegressionShouldEnhanceAndMockMathGame() throws Exception {
        Path moduleDir = moduleDir();
        Path targetDir = moduleDir.resolve("target");
        Path arthasBinZip = targetDir.resolve("arthas-bin.zip");
        Path originalPluginJar = findSingleFile(targetDir, "original-arthas-plugin-*.jar");

        Assertions.assertTrue(Files.isRegularFile(arthasBinZip), "arthas-bin.zip 应在 package 阶段生成");
        Assertions.assertTrue(Files.isRegularFile(originalPluginJar), "应存在 original-arthas-plugin 制品");

        Path tempDir = Files.createTempDirectory("arthas-artifact-regression-");
        Process mathGameProcess = null;
        try {
            unzip(arthasBinZip, tempDir);
            ProcessResult mergeResult = runProcess(command(javaExecutable(), "-jar", originalPluginJar.toString()),
                    tempDir, tempDir.resolve("merge.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, mergeResult.exitCode,
                    () -> "增强 arthas-core.jar 失败，输出:\n" + mergeResult.output);
            Assertions.assertTrue(mergeResult.output.contains("执行完成") || mergeResult.output.contains("Class已合并"),
                    () -> "增强输出不符合预期:\n" + mergeResult.output);

            Path enhancedCoreJar = tempDir.resolve("arthas-core.jar");
            Assertions.assertTrue(jarContains(enhancedCoreJar, "io/github/ly1806620741/arthas/plugin/MockCommand.class"),
                    "增强后的 arthas-core.jar 应包含 MockCommand");
//            Assertions.assertTrue(jarContains(enhancedCoreJar, "net/bytebuddy/matcher/ElementMatchers.class"),
//                    "增强后的 arthas-core.jar 应包含 ByteBuddy 运行时依赖");

            Path mathGameLog = tempDir.resolve("math-game.log");
            ProcessBuilder mathGameBuilder = new ProcessBuilder(javaExecutable(), "-jar", "math-game.jar");
            mathGameBuilder.directory(tempDir.toFile());
            mathGameBuilder.redirectErrorStream(true);
            mathGameBuilder.redirectOutput(mathGameLog.toFile());
            mathGameProcess = mathGameBuilder.start();

            waitForLogGrowth(mathGameLog, 1, Duration.ofSeconds(20));

            int telnetPort = findFreePort();
            String mockCommand = "options strict false;help mock;mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)'";
            ProcessResult attachResult = runProcess(
                    command(javaExecutable(), "-jar", tempDir.resolve("arthas-boot.jar").toString(),
                            "--select", "math-game",
                            "--target-ip", "127.0.0.1",
                            "--telnet-port", Integer.toString(telnetPort),
                            "--http-port", "-1",
                            "-c", mockCommand),
                    tempDir, tempDir.resolve("attach.log"), Duration.ofSeconds(90));

            Assertions.assertEquals(0, attachResult.exitCode,
                    () -> "arthas-boot 附着/执行 mock 失败，输出:\n" + attachResult.output);
            Assertions.assertFalse(attachResult.output.contains("command not found"),
                    () -> "mock 命令未生效，输出:\n" + attachResult.output);
            Assertions.assertTrue(attachResult.output.contains("mock") && attachResult.output.contains("help mock"),
                    () -> "附着输出中未发现 mock 命令执行痕迹:\n" + attachResult.output);

            String mathGameOutput = waitForMockedMathGameOutput(mathGameLog, Duration.ofSeconds(20));
            Assertions.assertTrue(MOCKED_OUTPUT_PATTERN.matcher(mathGameOutput).find(),
                    () -> "math-game 输出中未观察到 mock 后的固定结果:\n" + mathGameOutput);

            ProcessResult stopResult = runProcess(
                    command(javaExecutable(), "-jar", tempDir.resolve("arthas-boot.jar").toString(),
                            "--select", "math-game",
                            "--target-ip", "127.0.0.1",
                            "--telnet-port", Integer.toString(telnetPort),
                            "--http-port", "-1",
                            "-c", "stop"),
                    tempDir, tempDir.resolve("stop.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, stopResult.exitCode,
                    () -> "stop 命令执行失败，输出:\n" + stopResult.output);
        } finally {
            if (mathGameProcess != null) {
                mathGameProcess.destroy();
                if (!mathGameProcess.waitFor(10, TimeUnit.SECONDS)) {
                    mathGameProcess.destroyForcibly();
                }
            }
            deleteRecursively(tempDir);
        }
    }

    private static Path moduleDir() {
        try {
            Path testClassesDir = Paths.get(ArthasBootIntegrationIT.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return testClassesDir.getParent().getParent();
        } catch (Exception e) {
            throw new IllegalStateException("无法定位模块目录", e);
        }
    }

    private static Path findSingleFile(Path directory, String glob) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            for (Path path : stream) {
                return path;
            }
        }
        throw new IOException("未找到文件: " + glob + " in " + directory);
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static List<String> command(String... args) {
        List<String> command = new ArrayList<String>();
        for (String arg : args) {
            command.add(arg);
        }
        return command;
    }

    private static ProcessResult runProcess(List<String> command,
            Path workingDirectory,
            Path outputFile,
            Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(outputFile.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
        if (!finished) {
            throw new IllegalStateException("进程超时未退出: " + command + "\n输出:\n" + output);
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private static boolean jarContains(Path jarFile, String entryName) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile.toFile())) {
            return jar.getJarEntry(entryName) != null;
        }
    }

    private static void waitForLogGrowth(Path logFile, int minLines, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(logFile)) {
                List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                if (lines.size() >= minLines) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("math-game 在限定时间内未产生输出");
    }

    private static String waitForMockedMathGameOutput(Path logFile, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(logFile)) {
                String content = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
                if (MOCKED_OUTPUT_PATTERN.matcher(content).find()) {
                    return content;
                }
            }
            Thread.sleep(300);
        }
        return Files.exists(logFile)
                ? new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8)
                : "";
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void unzip(Path zipFile, Path destinationDir) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path target = destinationDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destinationDir)) {
                    throw new IOException("非法 zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zipInputStream, target);
                }
                zipInputStream.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try (Stream<Path> pathStream = Files.walk(path)) {
            pathStream.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String output;

        private ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}





