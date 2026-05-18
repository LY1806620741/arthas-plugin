package io.github.ly1806620741.arthas;

import java.io.*;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class ArthasJarMerger {
    private static final String ARTHAS_JAR_PATH = "arthas-core.jar";
    private static final String BAK_PATH = ARTHAS_JAR_PATH + ".bak";
    private static final String SELF_CLASS_NAME = ArthasJarMerger.class.getName().replace(".", "/") + ".class";
    private static final String TARGET_SPEC_VERSION = readArthasVersionFromConfig();

    private static String readArthasVersionFromConfig() {
        try (InputStream is = ArthasJarMerger.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (is == null) {
                System.err.println("⚠️ 未找到version.properties配置文件");
                return null;
            }
            Properties props = new Properties(); // 单独创建，不放入try-with-resources
            props.load(is);
            return props.getProperty("arthas.spec.version", "").trim();
        } catch (IOException e) {
            System.err.println("⚠️ 读取version.properties失败: " + e.getMessage());
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        File targetJar = new File(ARTHAS_JAR_PATH);
        if (!targetJar.exists()) {
            System.err.println("❌ 找不到文件: " + ARTHAS_JAR_PATH);
            return;
        }

        if (!checkManifestVersion(targetJar)) {
            System.err.println("❌ 目标JAR包版本不符合要求，需要Specification-Version: " + TARGET_SPEC_VERSION);
            return;
        }

        File bakFile = new File(BAK_PATH);

        String selfJar = getSelfJarPath();
        if (selfJar == null) {
            System.err.println("❌ 请将本程序打包为jar运行");
            return;
        }
        mergeIntoTargetJar(targetJar, bakFile, new File(selfJar));
        System.out.println("✅ 执行完成，Class已合并至原jar包");
    }

    static void mergeIntoTargetJar(File targetJar, File bakFile, File sourceJar) throws Exception {
        File baseJar = targetJar;
        if (!bakFile.exists()) {
            Files.copy(targetJar.toPath(), bakFile.toPath());
            System.out.println("✅ 备份成功: " + bakFile.getPath());
        } else {
            baseJar = bakFile;
            System.out.println("ℹ️ 备份文件已存在，将基于备份文件进行合并: " + bakFile.getPath());
        }
        mergeClass(baseJar, sourceJar, targetJar);
    }

    private static String getSelfJarPath() {
        try {
            return new File(ArthasJarMerger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean checkManifestVersion(File jarFile) {
        try (JarFile jf = new JarFile(jarFile)) {
            // 获取MANIFEST.MF文件
            Manifest manifest = jf.getManifest();
            if (manifest == null) {
                System.err.println("❌ 目标JAR包中未找到MANIFEST.MF文件");
                return false;
            }
            // 获取Manifest的主属性
            Attributes mainAttributes = manifest.getMainAttributes();
            // 读取Specification-Version属性值
            String specVersion = mainAttributes.getValue("Specification-Version");

            if (specVersion == null) {
                System.err.println("❌ MANIFEST.MF中未找到Specification-Version属性");
                return false;
            }

            System.out.println("ℹ️ 检测到目标JAR包版本: Specification-Version = " + specVersion);
            // 对比版本号是否匹配
            return TARGET_SPEC_VERSION.equals(specVersion);
        } catch (IOException e) {
            System.err.println("❌ 读取MANIFEST.MF时发生错误: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void mergeClass(File baseJar, File sourceJar, File outputJar) throws Exception {
        File tempJar = new File(outputJar.getAbsolutePath() + ".tmp");

        // 1. 先扫描 sourceJar，确定哪些文件是我们要覆盖进去的
        Set<String> sourceEntryNames = new HashSet<>();
        try (JarFile srcJf = new JarFile(sourceJar)) {
            Enumeration<JarEntry> srcEntries = srcJf.entries();
            while (srcEntries.hasMoreElements()) {
                JarEntry entry = srcEntries.nextElement();
                String name = entry.getName();
                // 满足过滤条件的文件才加入“覆盖名单”
                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals(SELF_CLASS_NAME)) {
                    sourceEntryNames.add(name);
                }
            }
        }

        // 2. 开始构建新的 JAR
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar));
                JarFile targetJf = new JarFile(baseJar);
                JarFile srcJf = new JarFile(sourceJar)) {

            // A. 搬运 targetJar，但跳过那些在 sourceJar 中已存在的文件
            Enumeration<JarEntry> targetEntries = targetJf.entries();
            while (targetEntries.hasMoreElements()) {
                JarEntry entry = targetEntries.nextElement();
                String name = entry.getName();

                if (sourceEntryNames.contains(name)) {
                    System.out.println("♻️ 发现同名类，将使用 source 中的版本覆盖: " + name);
                    continue; // 跳过旧版本，不写入 jos
                }
                copyEntry(targetJf, entry, jos);
            }

            // B. 将 sourceJar 中的新类全部写入
            for (String name : sourceEntryNames) {
                JarEntry entry = srcJf.getJarEntry(name);
                if (entry != null) {
                    copyEntry(srcJf, entry, jos);
                    System.out.println("📥 已写入新类(覆盖/新增): " + name);
                }
            }
        }

        // 3. 替换原始文件
        if (outputJar.delete()) {
            if (!tempJar.renameTo(outputJar)) {
                throw new IOException("重命名临时文件失败");
            }
        } else {
            throw new IOException("无法覆盖原 JAR 文件，请检查文件是否被占用");
        }
    }

    private static void copyEntry(JarFile jar, JarEntry entry, JarOutputStream jos) throws IOException {
        jos.putNextEntry(new JarEntry(entry.getName()));
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                jos.write(buf, 0, len);
            }
        }
        jos.closeEntry();
    }

}
