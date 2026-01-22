package com.taobao.arthas.core.view;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.taobao.arthas.core.GlobalOptions;
import com.taobao.arthas.core.util.StringUtils;

/**
 * @author ralf0131 2018-07-10 10:55.
 */
public class ObjectViewTest {

    @Test
    public void jacocoFilter() throws Exception {
        RuntimeData runtimeData = new RuntimeData();
        LoggerRuntime runtime = new LoggerRuntime();
        runtime.startup(runtimeData);
        Instrumenter instrumenter = new Instrumenter(runtime);

        Class<?> targetClass = ObjectViewTest.class;
        String classResource = "/" + targetClass.getName().replace(".", "/") + ".class";
        byte[] originalBytes;
        try (InputStream is = targetClass.getResourceAsStream(classResource);
                ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            originalBytes = bos.toByteArray();
        }

        byte[] enhancedBytes = instrumenter.instrument(originalBytes, targetClass.getName());

        Class<?> enhancedClass = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                // 验证：检查增强后的类是否包含$jacoco字段（JaCoCo注入的核心字段）
                if (name.equals(targetClass.getName())) {
                    Class<?> enhancedCls = defineClass(name, enhancedBytes, 0, enhancedBytes.length);
                    try {
                        Field jacocoField = enhancedCls.getDeclaredField("$jacocoData");
                        Assertions.assertNotNull(jacocoField,"jacoco enhance error");
                    } catch (NoSuchFieldException e) {
                        Assertions.fail("jacoco enhance error", e);
                    }
                    return enhancedCls;
                }
                return ClassLoader.getSystemClassLoader().loadClass(name);
            }
        }.loadClass(targetClass.getName());

        Object enhancedObj = enhancedClass.getDeclaredConstructor().newInstance();

        ObjectView objectView = new ObjectView(enhancedObj, 3);
        String draw = objectView.draw();
        Assertions.assertNotNull(draw);
        Assertions.assertTrue(!draw.contains("jacoco"));
        GlobalOptions.ignoreJacocoField = false;
        String draw2 = objectView.draw();
        Assertions.assertNotNull(draw2);
        Assertions.assertTrue(draw2.contains("jacoco"));
    }
}
