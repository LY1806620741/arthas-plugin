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
        Instrumenter instrumenter = new Instrumenter(new LoggerRuntime());
        String name = ObjectViewTest.class.getName();
        byte[] bytes;
        try (InputStream is = getClass().getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            bytes = instrumenter.instrument(is.readAllBytes(), name);
        }

        Class<?> clazz = new ClassLoader() {
            public Class<?> load(String n, byte[] b) { return defineClass(n, b, 0, b.length); }
        }.load(name, bytes);

        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object obj = ((sun.misc.Unsafe) f.get(null)).allocateInstance(clazz);

        ObjectView view = new ObjectView(obj, 3);

        Assertions.assertFalse(view.draw().contains("jacoco"), "应忽略jacoco字段");
        GlobalOptions.ignoreJacocoField = false;
        Assertions.assertTrue(view.draw().contains("jacoco"), "应包含jacoco字段");
    }
}
