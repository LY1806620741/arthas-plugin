package io.github.ly1806620741.arthas;

import java.io.*;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public class ArthasJarMerger {
    private static final String ARTHAS_JAR_PATH = "arthas-core.jar";
    private static final String BAK_PATH = ARTHAS_JAR_PATH + ".bak";
    private static final String SELF_CLASS_NAME = ArthasJarMerger.class.getName().replace(".", "/") + ".class";;

    public static void main(String[] args) throws Exception {
        File targetJar = new File(ARTHAS_JAR_PATH);
        if (!targetJar.exists()) {
            System.err.println("❌ 找不到文件: " + ARTHAS_JAR_PATH);
            return;
        }
        // 只备份一次，存在bak则跳过
        File bakFile = new File(BAK_PATH);
        if (!bakFile.exists()) {
            Files.copy(targetJar.toPath(), bakFile.toPath());
            System.out.println("✅ 备份成功: " + BAK_PATH);
        } else {
            System.out.println("ℹ️ 备份文件已存在，跳过备份");
        }

        String selfJar = getSelfJarPath();
        if (selfJar == null) {
            System.err.println("❌ 请将本程序打包为jar运行");
            return;
        }
        mergeClass(targetJar, new File(selfJar));
        System.out.println("✅ 执行完成，Class已合并至原jar包");
    }

    private static String getSelfJarPath() {
        try {
            return new File(ArthasJarMerger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void mergeClass(File targetJar, File sourceJar) throws Exception {
        JarFile srcJar = new JarFile(sourceJar);
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(targetJar, true));
        Enumeration<JarEntry> entries = srcJar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            // 只处理class文件 + 过滤META-INF目录，防止破坏原jar签名
            if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals(SELF_CLASS_NAME)) {
                jos.putNextEntry(new JarEntry(name));
                InputStream in = srcJar.getInputStream(entry);
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1)
                    jos.write(buf, 0, len);
                in.close();
                jos.closeEntry();
                System.out.println("📥 合并class: " + name);
            }
        }
        jos.close();
        srcJar.close();
    }
}