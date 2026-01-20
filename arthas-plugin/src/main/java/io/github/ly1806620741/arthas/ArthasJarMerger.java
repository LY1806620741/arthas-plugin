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
        File tempJar = new File(targetJar.getAbsolutePath() + ".tmp");
        
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
             JarFile targetJf = new JarFile(targetJar);
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
        if (targetJar.delete()) {
            if (!tempJar.renameTo(targetJar)) {
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
