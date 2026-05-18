package io.github.ly1806620741.arthas;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

class ArthasJarMergerTest {

    @Test
    @DisplayName("存在 bak 时应以 bak 为基准合并，但输出仍覆盖目标 jar")
    void shouldMergeUsingBakAsBaseWhenBakExists() throws Exception {
        Path tempDir = Files.createTempDirectory("arthas-jar-merger-");
        try {
            File targetJar = tempDir.resolve("arthas-core.jar").toFile();
            File bakJar = tempDir.resolve("arthas-core.jar.bak").toFile();
            File sourceJar = tempDir.resolve("plugin.jar").toFile();

            writeJar(targetJar, mapOf(
                    "target/OnlyInTarget.class", "target-only"
            ));
            writeJar(bakJar, mapOf(
                    "base/OnlyInBak.class", "bak-only"
            ));
            writeJar(sourceJar, mapOf(
                    "plugin/OnlyInSource.class", "source-only"
            ));

            ArthasJarMerger.mergeIntoTargetJar(targetJar, bakJar, sourceJar);

            Assertions.assertEquals("bak-only", readEntry(targetJar, "base/OnlyInBak.class"));
            Assertions.assertEquals("source-only", readEntry(targetJar, "plugin/OnlyInSource.class"));
            Assertions.assertNull(readEntry(targetJar, "target/OnlyInTarget.class"),
                    "目标 jar 不应继续保留仅存在于旧 target 的内容，应基于 bak 重建");

            Assertions.assertEquals("bak-only", readEntry(bakJar, "base/OnlyInBak.class"),
                    "bak 文件应保持不变");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static Map<String, String> mapOf(String name, String value) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(name, value);
        return entries;
    }

    private static void writeJar(File jarFile, Map<String, String> entries) throws IOException {
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarFile.toPath()))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                outputStream.putNextEntry(new JarEntry(entry.getKey()));
                outputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();
            }
        }
    }

    private static String readEntry(File jarFile, String entryName) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream inputStream = jar.getInputStream(entry)) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }
        Files.walk(path)
                .sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(current -> {
                    try {
                        Files.deleteIfExists(current);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}

