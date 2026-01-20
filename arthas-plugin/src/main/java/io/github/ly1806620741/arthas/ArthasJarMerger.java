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
        // 1. 创建一个临时文件
        File tempJar = new File(targetJar.getAbsolutePath() + ".tmp");
        
        // 使用 try-with-resources 自动关闭资源
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar));
             JarFile targetJf = new JarFile(targetJar);
             JarFile srcJf = new JarFile(sourceJar)) {
    
            // 2. 将原 targetJar 的所有旧内容搬运到临时 JAR
            Enumeration<JarEntry> targetEntries = targetJf.entries();
            while (targetEntries.hasMoreElements()) {
                JarEntry entry = targetEntries.nextElement();
                // 复制每一个旧 Entry 到新流
                copyEntry(targetJf, entry, jos);
            }
    
            // 3. 合并 sourceJar 中的新类文件
            Enumeration<JarEntry> srcEntries = srcJf.entries();
            while (srcEntries.hasMoreElements()) {
                JarEntry entry = srcEntries.nextElement();
                String name = entry.getName();
                
                // 过滤逻辑
                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals(SELF_CLASS_NAME)) {
                    // 注意：如果 target 原本已有同名类，此处 copy 会导致 ZIP 重复项异常
                    // 建议增加判断逻辑：若 targetEntries 已包含此 name 则跳过或处理覆盖
                    copyEntry(srcJf, entry, jos);
                    System.out.println("📥 已合并新类: " + name);
                }
            }
        } // 此时临时文件已完成新老目录的重新构建
    
        // 4. 替换原始文件
        if (targetJar.delete()) {
            tempJar.renameTo(targetJar);
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
