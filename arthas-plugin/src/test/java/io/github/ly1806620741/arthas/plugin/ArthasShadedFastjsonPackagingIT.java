package io.github.ly1806620741.arthas.plugin;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ArthasShadedFastjsonPackagingIT {

    private static final String MOCK_COMMAND_ADVICE_ENTRY =
            "io/github/ly1806620741/arthas/plugin/MockCommand$OgnlMockAdvice.class";
    private static final String SHADED_JSON_REF = "com/alibaba/arthas/deps/com/alibaba/fastjson2/JSON";
    private static final String SHADED_JSON_READER_REF =
            "com/alibaba/arthas/deps/com/alibaba/fastjson2/JSONReader$Feature";
    private static final String ORIGINAL_JSON_REF = "com/alibaba/fastjson2/JSON";
    private static final String ORIGINAL_JSON_READER_REF = "com/alibaba/fastjson2/JSONReader$Feature";

    @Test
    void packagedJarShouldRelocateFastjsonReferencesWithoutBundlingFastjsonClasses() throws Exception {
        File jarFile = findPackagedJar();
        Assertions.assertTrue(jarFile.isFile(), "packaged jar not found: " + jarFile.getAbsolutePath());

        try (JarFile jar = new JarFile(jarFile)) {
            Assertions.assertNull(jar.getEntry("com/alibaba/fastjson2/JSON.class"));
            Assertions.assertNull(jar.getEntry("com/alibaba/arthas/deps/com/alibaba/fastjson2/JSON.class"));

            JarEntry adviceEntry = jar.getJarEntry(MOCK_COMMAND_ADVICE_ENTRY);
            Assertions.assertNotNull(adviceEntry, "missing entry: " + MOCK_COMMAND_ADVICE_ENTRY);

            List<String> utf8Constants = readUtf8Constants(jar, adviceEntry);
            Assertions.assertTrue(utf8Constants.contains(SHADED_JSON_REF),
                    "expected shaded JSON reference in packaged OgnlMockAdvice.class");
            Assertions.assertTrue(utf8Constants.contains(SHADED_JSON_READER_REF),
                    "expected shaded JSONReader reference in packaged OgnlMockAdvice.class");
            Assertions.assertFalse(utf8Constants.contains(ORIGINAL_JSON_REF),
                    "packaged OgnlMockAdvice.class should not keep original JSON reference");
            Assertions.assertFalse(utf8Constants.contains(ORIGINAL_JSON_READER_REF),
                    "packaged OgnlMockAdvice.class should not keep original JSONReader reference");
        }
    }

    private File findPackagedJar() {
        File targetDir = new File(System.getProperty("user.dir"), "target");
        File[] matches = targetDir.listFiles(file -> file.isFile()
                && file.getName().startsWith("arthas-plugin-")
                && file.getName().endsWith(".jar")
                && !file.getName().endsWith("-sources.jar")
                && !file.getName().endsWith("-tests.jar"));
        Assertions.assertNotNull(matches, "target directory is unavailable: " + targetDir.getAbsolutePath());
        Assertions.assertTrue(matches.length > 0, "no packaged jar found under " + targetDir.getAbsolutePath());
        return Arrays.stream(matches)
                .max(Comparator.comparingLong(File::lastModified))
                .orElseThrow(() -> new AssertionError("no packaged jar found under " + targetDir.getAbsolutePath()));
    }

    private List<String> readUtf8Constants(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream inputStream = jar.getInputStream(entry);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            return readUtf8Constants(outputStream.toByteArray());
        }
    }

    private List<String> readUtf8Constants(byte[] classBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(classBytes))) {
            Assertions.assertEquals(0xCAFEBABE, input.readInt(), "invalid class file header");
            input.readUnsignedShort();
            input.readUnsignedShort();

            int constantPoolCount = input.readUnsignedShort();
            List<String> utf8Constants = new ArrayList<>();
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8Constants.add(input.readUTF());
                        break;
                    case 3:
                    case 4:
                        input.readInt();
                        break;
                    case 5:
                    case 6:
                        input.readLong();
                        index++;
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        input.readUnsignedShort();
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        input.readUnsignedShort();
                        input.readUnsignedShort();
                        break;
                    case 15:
                        input.readUnsignedByte();
                        input.readUnsignedShort();
                        break;
                    default:
                        throw new IOException("unsupported constant pool tag: " + tag);
                }
            }
            return utf8Constants;
        }
    }
}


