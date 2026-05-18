package io.github.ly1806620741.arthas.plugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ly1806620741.arthas.plugin.itapp.ArthasSpringBootProxyTargetApp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class ArthasBootIntegrationIT {

    private static final String STRICT_PROMPT_FRAGMENT = "execute `options strict false`";

    @Test
    @DisplayName("使用 original 插件增强官方 arthas-core 后验证 strict 模式下 mock 给出手动关闭提示")
    void artifactRegressionShouldPromptManualStrictDisableBeforeMock() throws Exception {
        PreparedArtifact artifact = prepareEnhancedArtifact("arthas-artifact-regression-");
        Path tempDir = artifact.tempDir;
        Process mathGameProcess = null;
        try {
            Assertions.assertTrue(jarContains(artifact.enhancedCoreJar, "io/github/ly1806620741/arthas/plugin/MockCommand.class"),
                    "增强后的 arthas-core.jar 应包含 MockCommand");

            Path mathGameLog = tempDir.resolve("math-game.log");
            mathGameProcess = launchMathGame(tempDir, mathGameLog);
            String targetPid = processId(mathGameProcess);

            waitForLogGrowth(mathGameLog, 1, Duration.ofSeconds(20));

            int telnetPort = findFreePort();
            String mockCommand = "help mock;mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)'";
            ProcessResult attachResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, mockCommand, tempDir.resolve("attach.log"), Duration.ofSeconds(90));

            Assertions.assertEquals(0, attachResult.exitCode,
                    () -> "arthas-boot 附着/执行 mock 失败，输出:\n" + attachResult.output);
            Assertions.assertFalse(attachResult.output.contains("command not found"),
                    () -> "mock 命令未生效，输出:\n" + attachResult.output);
            Assertions.assertTrue(attachResult.output.contains("mock") && attachResult.output.contains("help mock"),
                    () -> "附着输出中未发现 mock 命令执行痕迹:\n" + attachResult.output);
            Assertions.assertTrue(attachResult.output.matches(
                            "(?s).*plugin_version\\s+0\\.0\\.1 \\(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\).*"),
                    () -> "附着输出中未发现 plugin_version banner 信息:\n" + attachResult.output);

            String mathGameOutput = waitForText(mathGameLog, STRICT_PROMPT_FRAGMENT, Duration.ofSeconds(20));
            Assertions.assertTrue(mathGameOutput.contains(STRICT_PROMPT_FRAGMENT),
                    () -> "math-game 输出中未观察到 strict 手动关闭提示:\n" + mathGameOutput);

            ProcessResult stopResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "stop", tempDir.resolve("stop.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, stopResult.exitCode,
                    () -> "stop 命令执行失败，输出:\n" + stopResult.output);
        } finally {
            destroyProcess(mathGameProcess);
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("使用 original 插件增强官方 arthas-core 后验证 mock --list 可查看已安装规则")
    void artifactRegressionShouldListInstalledMocks() throws Exception {
        PreparedArtifact artifact = prepareEnhancedArtifact("arthas-mock-list-regression-");
        Path tempDir = artifact.tempDir;
        Process mathGameProcess = null;
        try {
            Path mathGameLog = tempDir.resolve("math-game-list.log");
            mathGameProcess = launchMathGame(tempDir, mathGameLog);
            String targetPid = processId(mathGameProcess);

            waitForLogGrowth(mathGameLog, 1, Duration.ofSeconds(20));

            int telnetPort = findFreePort();
            ProcessResult strictResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "options strict false", tempDir.resolve("mock-list-options.log"), Duration.ofSeconds(90));
            Assertions.assertEquals(0, strictResult.exitCode,
                    () -> "关闭 strict 模式失败，输出:\n" + strictResult.output);

            String mockCommand = "mock demo.MathGame primeFactors -b '#this.returnObj=@java.util.Arrays@asList(99991,99989)';"
                    + "mock --list";
            ProcessResult attachResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, mockCommand, tempDir.resolve("mock-list-attach.log"), Duration.ofSeconds(90));

            Assertions.assertEquals(0, attachResult.exitCode,
                    () -> "arthas-boot 附着/执行 mock --list 失败，输出:\n" + attachResult.output);
            Assertions.assertFalse(attachResult.output.contains("command not found"),
                    () -> "mock 命令未生效，输出:\n" + attachResult.output);
            Assertions.assertTrue(attachResult.output.contains("Active mocks:")
                            && attachResult.output.contains("demo.MathGame#primeFactors")
                            && attachResult.output.contains("strict=false"),
                    () -> "附着输出中未发现 mock 列表内容:\n" + attachResult.output);

            ProcessResult stopResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "stop", tempDir.resolve("mock-list-stop.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, stopResult.exitCode,
                    () -> "stop 命令执行失败，输出:\n" + stopResult.output);
        } finally {
            destroyProcess(mathGameProcess);
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("使用 original 插件增强官方 arthas-core 后验证 springboot --proxy-http 可注入转发路由")
    void artifactRegressionShouldInjectSpringBootProxyRoute() throws Exception {
        PreparedArtifact artifact = prepareEnhancedArtifact("arthas-springboot-proxy-regression-");
        Path tempDir = artifact.tempDir;
        Process springBootProcess = null;
        HttpServer backendServer = null;
        try {
            Assertions.assertTrue(
                    jarContains(artifact.enhancedCoreJar, "io/github/ly1806620741/arthas/plugin/SpringBootCommand.class"),
                    "增强后的 arthas-core.jar 应包含 SpringBootCommand");

            int backendPort = findFreePort();
            backendServer = startEchoServer(backendPort);

            int appPort = findFreePort();
            Path springBootLog = tempDir.resolve("spring-boot-app.log");
            springBootProcess = launchSpringBootProxyTargetApp(artifact.moduleDir, springBootLog, appPort);
            String targetPid = processId(springBootProcess);

            waitForHttpText(new URL("http://127.0.0.1:" + appPort + "/sample"), "sample", Duration.ofSeconds(40));
            ensureProcessAlive(springBootProcess, springBootLog);

            int telnetPort = findFreePort();
            String routePattern = "/arthas/plain/**";
            String springbootCommand = "help springboot;springboot --proxy-http --route " + routePattern
                    + " --target-host 127.0.0.1 --target-port " + backendPort;
            ProcessResult attachResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, springbootCommand, tempDir.resolve("springboot-attach.log"), Duration.ofSeconds(90));

            Assertions.assertEquals(0, attachResult.exitCode,
                    () -> "arthas-boot 附着/执行 springboot 命令失败，输出:\n" + attachResult.output);
            Assertions.assertFalse(attachResult.output.contains("command not found"),
                    () -> "springboot 命令未生效，输出:\n" + attachResult.output);
            Assertions.assertTrue(attachResult.output.contains("help springboot")
                            && attachResult.output.contains("Route injected successfully: " + routePattern + " -> 127.0.0.1:" + backendPort),
                    () -> "附着输出中未发现 springboot 命令执行成功痕迹:\n" + attachResult.output);

            String proxiedOutput = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/arthas/plain/api/test?foo=bar"),
                    "GET /api/test?foo=bar", Duration.ofSeconds(20));
            Assertions.assertTrue(proxiedOutput.contains("GET /api/test?foo=bar"),
                    () -> "未通过 springboot 代理观察到后端响应:\n" + proxiedOutput);

            ProcessResult stopResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "stop", tempDir.resolve("springboot-stop.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, stopResult.exitCode,
                    () -> "stop 命令执行失败，输出:\n" + stopResult.output);
        } finally {
            if (backendServer != null) {
                backendServer.stop(0);
            }
            destroyProcess(springBootProcess);
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("使用 original 插件增强官方 arthas-core 后验证可 mock Spring CGLIB 代理并通过 --list 查看")
    void artifactRegressionShouldMockSpringCglibAndListIt() throws Exception {
        PreparedArtifact artifact = prepareEnhancedArtifact("arthas-spring-cglib-regression-");
        Path tempDir = artifact.tempDir;
        Process springBootProcess = null;
        try {
            int appPort = findFreePort();
            Path springBootLog = tempDir.resolve("spring-cglib-app.log");
            springBootProcess = launchSpringBootProxyTargetApp(artifact.moduleDir, springBootLog, appPort);
            String targetPid = processId(springBootProcess);

            waitForHttpText(new URL("http://127.0.0.1:" + appPort + "/sample"), "sample", Duration.ofSeconds(40));
            String proxyClassName = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/mock/cglib/class-name"),
                    "CGLIB", Duration.ofSeconds(20));
            Assertions.assertTrue(proxyClassName.contains("CGLIB"),
                    () -> "未获得 CGLIB 代理类名:\n" + proxyClassName);
            String originalValue = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/mock/cglib/value"),
                    "origin", Duration.ofSeconds(20));
            Assertions.assertTrue(originalValue.contains("origin"),
                    () -> "mock 前返回值不符合预期:\n" + originalValue);
            ensureProcessAlive(springBootProcess, springBootLog);

            int telnetPort = findFreePort();
            ProcessResult strictResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "options strict false", tempDir.resolve("spring-cglib-options.log"), Duration.ofSeconds(90));
            Assertions.assertEquals(0, strictResult.exitCode,
                    () -> "关闭 strict 模式失败，输出:\n" + strictResult.output);

            String mockCommand = "mock " + proxyClassName + " value -a '#this.returnObj=new java.lang.String(\"mock-cglib\")';"
                    + "mock --list";
            ProcessResult attachResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, mockCommand, tempDir.resolve("spring-cglib-attach.log"), Duration.ofSeconds(90));

            Assertions.assertEquals(0, attachResult.exitCode,
                    () -> "arthas-boot 附着/执行 Spring CGLIB mock 失败，输出:\n" + attachResult.output);
            Assertions.assertFalse(attachResult.output.contains("command not found"),
                    () -> "mock 命令未生效，输出:\n" + attachResult.output);
            Assertions.assertTrue(attachResult.output.contains("Active mocks:")
                            && attachResult.output.contains(ArthasSpringBootProxyTargetApp.CglibProxyTarget.class.getName() + "#value")
                            && attachResult.output.contains("strict=false"),
                    () -> "附着输出中未发现 Spring CGLIB mock 列表内容:\n" + attachResult.output);

            String mockedValue = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/mock/cglib/debug-value"),
                    "mock-cglib", Duration.ofSeconds(20));
            Assertions.assertTrue(mockedValue.contains("mock-cglib"),
                    () -> "Spring CGLIB mock 后返回值不符合预期:\n" + mockedValue);

            ProcessResult stopResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "stop", tempDir.resolve("spring-cglib-stop.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, stopResult.exitCode,
                    () -> "stop 命令执行失败，输出:\n" + stopResult.output);
        } finally {
            destroyProcess(springBootProcess);
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("使用 original 插件增强官方 arthas-core 后验证可 mock Controller 的 List<T> 与 GenericListResult<T> 返回")
    void artifactRegressionShouldMockControllerGenericReturns() throws Exception {
        PreparedArtifact artifact = prepareEnhancedArtifact("arthas-controller-generic-regression-");
        Path tempDir = artifact.tempDir;
        Process springBootProcess = null;
        try {
            int appPort = findFreePort();
            Path springBootLog = tempDir.resolve("spring-controller-generic-app.log");
            springBootProcess = launchSpringBootProxyTargetApp(artifact.moduleDir, springBootLog, appPort);
            String targetPid = processId(springBootProcess);

            String controllerClassName = ArthasSpringBootProxyTargetApp.class.getName() + "$SampleController";

            String originalList = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/mock/controller/list"),
                    "origin", Duration.ofSeconds(40));
            Assertions.assertTrue(originalList.contains("origin"),
                    () -> "mock 前 controller list 返回不符合预期:\n" + originalList);

            String originalGenericResult = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/mock/controller/generic-list-result"),
                    "origin", Duration.ofSeconds(20));
            Assertions.assertTrue(originalGenericResult.contains("origin"),
                    () -> "mock 前 generic result 返回不符合预期:\n" + originalGenericResult);

            ensureProcessAlive(springBootProcess, springBootLog);

            int telnetPort = findFreePort();
            ProcessResult strictResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "options strict false",
                    tempDir.resolve("spring-controller-generic-options.log"), Duration.ofSeconds(90));
            Assertions.assertEquals(0, strictResult.exitCode,
                    () -> "关闭 strict 模式失败，输出:\n" + strictResult.output);

            String mockCommand = "mock " + controllerClassName + " controllerList"
                    + " -j '[{\"name\":\"controller-list\",\"child\":{\"city\":\"hangzhou\"}}]'"
                    + " -a '#this.returnObj=#json';"
                    + "mock " + controllerClassName + " controllerGenericListResult"
                    + " -j '{\"code\":\"200\",\"items\":[{\"name\":\"controller-generic\",\"child\":{\"city\":\"shenzhen\"}}]}'"
                    + " -a '#this.returnObj=#json';"
                    + "mock --list";
            ProcessResult attachResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, mockCommand,
                    tempDir.resolve("spring-controller-generic-attach.log"), Duration.ofSeconds(90));

            Assertions.assertEquals(0, attachResult.exitCode,
                    () -> "arthas-boot 附着/执行 controller 泛型 mock 失败，输出:\n" + attachResult.output);
            Assertions.assertFalse(attachResult.output.contains("command not found"),
                    () -> "mock 命令未生效，输出:\n" + attachResult.output);
            Assertions.assertTrue(attachResult.output.contains("Active mocks:")
                            && attachResult.output.contains(controllerClassName + "#controllerList")
                            && attachResult.output.contains(controllerClassName + "#controllerGenericListResult"),
                    () -> "附着输出中未发现 controller 泛型 mock 列表内容:\n" + attachResult.output);

            String mockedList = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/mock/controller/list"),
                    "controller-list", Duration.ofSeconds(20));
            Assertions.assertTrue(mockedList.contains("controller-list") && mockedList.contains("hangzhou"),
                    () -> "mock 后 controller list 返回不符合预期:\n" + mockedList);

            String mockedGenericResult = waitForHttpText(
                    new URL("http://127.0.0.1:" + appPort + "/mock/controller/generic-list-result"),
                    "controller-generic", Duration.ofSeconds(20));
            Assertions.assertTrue(mockedGenericResult.contains("\"code\":\"200\"")
                            && mockedGenericResult.contains("controller-generic")
                            && mockedGenericResult.contains("shenzhen"),
                    () -> "mock 后 generic result 返回不符合预期:\n" + mockedGenericResult);

            ProcessResult stopResult = runArthasBootCommand(
                    tempDir, telnetPort, targetPid, "stop",
                    tempDir.resolve("spring-controller-generic-stop.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, stopResult.exitCode,
                    () -> "stop 命令执行失败，输出:\n" + stopResult.output);
        } finally {
            destroyProcess(springBootProcess);
            deleteRecursively(tempDir);
        }
    }

    private static PreparedArtifact prepareEnhancedArtifact(String tempDirPrefix) throws Exception {
        Path moduleDir = moduleDir();
        Path targetDir = moduleDir.resolve("target");
        Path arthasBinZip = resolveArthasBinZip(moduleDir, targetDir);
        Path originalPluginJar = resolveOriginalPluginJar(moduleDir, targetDir);

        Assertions.assertTrue(Files.isRegularFile(arthasBinZip), () -> "未找到可用的 arthas-bin.zip: " + arthasBinZip);
        Assertions.assertTrue(Files.isRegularFile(originalPluginJar), "应存在 arthas-plugin 制品");

        Path tempDir = Files.createTempDirectory(tempDirPrefix);
        try {
            unzip(arthasBinZip, tempDir);
            ProcessResult mergeResult = runProcess(command(javaExecutable(), "-jar", originalPluginJar.toString()),
                    tempDir, tempDir.resolve("merge.log"), Duration.ofSeconds(60));
            Assertions.assertEquals(0, mergeResult.exitCode,
                    () -> "增强 arthas-core.jar 失败，输出:\n" + mergeResult.output);
            Assertions.assertTrue(mergeResult.output.contains("执行完成") || mergeResult.output.contains("Class已合并"),
                    () -> "增强输出不符合预期:\n" + mergeResult.output);
            return new PreparedArtifact(moduleDir, tempDir, tempDir.resolve("arthas-core.jar"));
        } catch (Throwable throwable) {
            deleteRecursively(tempDir);
            throw throwable;
        }
    }

    private static Path moduleDir() {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(System.getProperty("user.dir")));
        try {
            candidates.add(Paths.get(ArthasBootIntegrationIT.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()));
        } catch (Exception ignore) {
            // ignore and continue with user.dir fallback
        }
        for (Path candidate : candidates) {
            Path resolved = findModuleDir(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        throw new IllegalStateException("无法定位模块目录，候选路径: " + candidates);
    }

    private static Path findModuleDir(Path start) {
        if (start == null) {
            return null;
        }
        Path current = Files.isDirectory(start)
                ? start.toAbsolutePath().normalize()
                : start.toAbsolutePath().normalize().getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && (Files.isDirectory(current.resolve("src/main/java"))
                            || Files.isDirectory(current.resolve("src/test/java")))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path resolveOriginalPluginJar(Path moduleDir, Path targetDir) throws Exception {
        try {
            return findSingleFile(targetDir, "arthas-plugin-*.jar");
        } catch (IOException ignored) {
            buildPluginJar(moduleDir);
            return findSingleFile(targetDir, "arthas-plugin-*.jar");
        }
    }

    private static void buildPluginJar(Path moduleDir) throws Exception {
        Path reactorDir = moduleDir.getParent() == null ? moduleDir : moduleDir.getParent();
        ProcessResult packageResult = runProcess(
                command("mvn", "-q", "-pl", moduleDir.getFileName().toString(), "-am", "-DskipTests", "package"),
                reactorDir,
                moduleDir.resolve("target").resolve("package-plugin.log"),
                Duration.ofMinutes(5));
        Assertions.assertEquals(0, packageResult.exitCode,
                () -> "自动打包 arthas-plugin 制品失败，输出:\n" + packageResult.output);
    }

    private static Path findSingleFile(Path directory, String glob) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            for (Path path : stream) {
                return path;
            }
        }
        throw new IOException("未找到文件: " + glob + " in " + directory);
    }

    private static Path resolveArthasBinZip(Path moduleDir, Path targetDir) throws Exception {
        Path packagedZip = targetDir.resolve("arthas-bin.zip");
        if (Files.isRegularFile(packagedZip)) {
            return packagedZip;
        }

        String arthasVersion = readArthasVersion();
        Path mavenZip = Paths.get(System.getProperty("user.home"), ".m2", "repository",
                "com", "taobao", "arthas", "arthas-packaging", arthasVersion,
                "arthas-packaging-" + arthasVersion + "-bin.zip");
        if (Files.isRegularFile(mavenZip)) {
            return mavenZip;
        }

        fetchArthasBinZip(moduleDir, arthasVersion);
        if (Files.isRegularFile(mavenZip)) {
            return mavenZip;
        }

        throw new IOException("未找到 arthas-bin.zip，既不存在于 target 目录，也不存在于本地 Maven 仓库: " + mavenZip);
    }

    private static void fetchArthasBinZip(Path moduleDir, String arthasVersion) throws Exception {
        String artifact = "com.taobao.arthas:arthas-packaging:" + arthasVersion + ":zip:bin";
        ProcessResult downloadResult = runProcess(
                command("mvn", "-q", "dependency:get", "-Dartifact=" + artifact),
                moduleDir.getParent(),
                moduleDir.resolve("target").resolve("fetch-arthas-bin.log"),
                Duration.ofMinutes(3));
        Assertions.assertEquals(0, downloadResult.exitCode,
                () -> "自动拉取 arthas bin 失败，artifact=" + artifact + "\n输出:\n" + downloadResult.output);
    }

    private static String readArthasVersion() throws IOException {
        try (InputStream inputStream = ArthasBootIntegrationIT.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (inputStream == null) {
                throw new IOException("未找到 version.properties");
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            String version = properties.getProperty("arthas.spec.version");
            if (version == null || version.trim().isEmpty() || version.contains("${")) {
                throw new IOException("version.properties 中的 arthas.spec.version 无效: " + version);
            }
            return version.trim();
        }
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String testRuntimeClasspath() {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.trim().isEmpty()) {
            classpath = System.getProperty("java.class.path");
        }
        return classpath;
    }

    private static List<String> command(String... args) {
        List<String> command = new ArrayList<String>();
        java.util.Collections.addAll(command, args);
        return command;
    }

    private static ProcessResult runArthasBootCommand(Path arthasHome,
            int telnetPort,
            String targetPid,
            String arthasCommand,
            Path outputFile,
            Duration timeout) throws Exception {
        return runProcess(
                command(javaExecutable(), "-jar", arthasHome.resolve("arthas-boot.jar").toString(),
                        "--target-ip", "127.0.0.1",
                        "--telnet-port", Integer.toString(telnetPort),
                        "--http-port", "-1",
                        "-c", arthasCommand,
                        targetPid),
                arthasHome,
                outputFile,
                timeout);
    }

    private static ProcessResult runProcess(List<String> command,
            Path workingDirectory,
            Path outputFile,
            Duration timeout) throws Exception {
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
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

    private static HttpServer startEchoServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new EchoHandler());
        server.start();
        return server;
    }

    private static String processId(Process process) throws Exception {
        try {
            Object pid = Process.class.getMethod("pid").invoke(process);
            if (pid != null) {
                return String.valueOf(pid);
            }
        } catch (NoSuchMethodException ignore) {
            // fall through for JDK 8 compile/runtime compatibility
        }
        try {
            java.lang.reflect.Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return String.valueOf(pidField.get(process));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法解析目标进程 pid: " + process.getClass().getName(), e);
        }
    }

    private static void ensureProcessAlive(Process process, Path logFile) throws IOException {
        try {
            int exitCode = process.exitValue();
            String log = Files.exists(logFile)
                    ? new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8)
                    : "";
            throw new IllegalStateException("目标 Spring Boot 进程已提前退出，exitCode=" + exitCode + "，日志:\n" + log);
        } catch (IllegalThreadStateException ignore) {
            // still alive
        }
    }

    private static Process launchMathGame(Path workingDirectory, Path logFile) throws IOException {
        ProcessBuilder mathGameBuilder = new ProcessBuilder(javaExecutable(), "-jar", "math-game.jar");
        mathGameBuilder.directory(workingDirectory.toFile());
        mathGameBuilder.redirectErrorStream(true);
        mathGameBuilder.redirectOutput(logFile.toFile());
        return mathGameBuilder.start();
    }

    private static Process launchSpringBootProxyTargetApp(Path moduleDir, Path logFile, int appPort) throws IOException {
        ProcessBuilder springBootBuilder = new ProcessBuilder(
                command(javaExecutable(), "-cp", testRuntimeClasspath(),
                        ArthasSpringBootProxyTargetApp.class.getName(), "--server.port=" + appPort));
        springBootBuilder.directory(moduleDir.toFile());
        springBootBuilder.redirectErrorStream(true);
        springBootBuilder.redirectOutput(logFile.toFile());
        return springBootBuilder.start();
    }

    private static void destroyProcess(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
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

    private static String waitForText(Path logFile, String expectedText, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(logFile)) {
                String content = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
                if (content.contains(expectedText)) {
                    return content;
                }
            }
            Thread.sleep(300);
        }
        return Files.exists(logFile)
                ? new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8)
                : "";
    }

    private static String waitForHttpText(URL url, String expectedText, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastResponse = "";
        while (System.nanoTime() < deadline) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setRequestMethod("GET");
                int status = connection.getResponseCode();
                InputStream bodyStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                lastResponse = bodyStream == null ? "" : new String(readAllBytes(bodyStream), StandardCharsets.UTF_8);
                if (status == 200 && lastResponse.contains(expectedText)) {
                    return lastResponse;
                }
            } catch (IOException e) {
                lastResponse = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Thread.sleep(300);
        }
        return lastResponse;
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

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        try (InputStream in = inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
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

    private static final class PreparedArtifact {
        private final Path moduleDir;
        private final Path tempDir;
        private final Path enhancedCoreJar;

        private PreparedArtifact(Path moduleDir, Path tempDir, Path enhancedCoreJar) {
            this.moduleDir = moduleDir;
            this.tempDir = tempDir;
            this.enhancedCoreJar = enhancedCoreJar;
        }
    }

    private static final class EchoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
            String query = exchange.getRequestURI().getRawQuery();
            String payload = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + (query == null ? "" : "?" + query)
                    + (body.isEmpty() ? "" : " " + body);
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }
    }
}





