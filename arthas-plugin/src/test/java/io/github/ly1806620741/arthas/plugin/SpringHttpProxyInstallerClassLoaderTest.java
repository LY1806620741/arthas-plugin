package io.github.ly1806620741.arthas.plugin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collection;

class SpringHttpProxyInstallerClassLoaderTest {

    @Test
    void findApplicationContextsShouldInspectLoadedClassClassLoader() throws Exception {
        Object marker = new Object();
        LiveBeansViewIsolatedClassLoader classLoader = new LiveBeansViewIsolatedClassLoader(
                SpringHttpProxyInstallerClassLoaderTest.class.getClassLoader());
        Class<?> isolatedLiveBeansViewClass = Class.forName(
                "org.springframework.context.support.LiveBeansView", true, classLoader);
        Collection<Object> isolatedContexts = applicationContexts(isolatedLiveBeansViewClass);
        isolatedContexts.add(marker);

        Instrumentation instrumentation = Mockito.mock(Instrumentation.class);
        Mockito.when(instrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[] { isolatedLiveBeansViewClass });

        try {
            Collection<Object> discovered = SpringHttpProxyInstaller.findApplicationContexts(instrumentation);
            Assertions.assertTrue(discovered.contains(marker),
                    "should discover application contexts from the target class loader");
        } finally {
            isolatedContexts.remove(marker);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> applicationContexts(Class<?> liveBeansViewClass) throws Exception {
        Field field = liveBeansViewClass.getDeclaredField("applicationContexts");
        field.setAccessible(true);
        return (Collection<Object>) field.get(null);
    }

    private static final class LiveBeansViewIsolatedClassLoader extends ClassLoader {
        private static final String TARGET_CLASS = "org.springframework.context.support.LiveBeansView";

        private LiveBeansViewIsolatedClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!TARGET_CLASS.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loadedClass = findLoadedClass(name);
                if (loadedClass == null) {
                    loadedClass = defineTargetClass(name);
                }
                if (resolve) {
                    resolveClass(loadedClass);
                }
                return loadedClass;
            }
        }

        private Class<?> defineTargetClass(String name) throws ClassNotFoundException {
            String resourceName = name.replace('.', '/') + ".class";
            try (InputStream inputStream = getParent().getResourceAsStream(resourceName)) {
                if (inputStream == null) {
                    throw new ClassNotFoundException(name + " resource not found");
                }
                byte[] bytes = readAllBytes(inputStream);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        private byte[] readAllBytes(InputStream inputStream) throws IOException {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }
}

