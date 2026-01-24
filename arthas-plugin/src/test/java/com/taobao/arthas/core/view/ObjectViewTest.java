package com.taobao.arthas.core.view;

import java.io.InputStream;

import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.LoggerRuntime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.taobao.arthas.common.IOUtils;
import com.taobao.arthas.core.GlobalOptions;

/**
 * 增加的case
 */
public class ObjectViewTest {

    /**
     * https://github.com/alibaba/arthas/issues/2778
     * @throws Exception
     */
    @Test
    public void jacocoFilter() throws Exception {
        Instrumenter instrumenter = new Instrumenter(new LoggerRuntime());
        String name = ObjectViewTest.class.getName();
        byte[] bytes;
        try (InputStream is = getClass().getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            bytes = instrumenter.instrument(IOUtils.getBytes(is), name);
        }

        Class<?> clazz = new ClassLoader() {
            public Class<?> load(String n, byte[] b) {
                return defineClass(n, b, 0, b.length);
            }
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
