package io.github.ly1806620741.arthas.plugin;

import com.taobao.arthas.core.enhancer.Enhancer;
import com.taobao.arthas.core.enhancer.EnhancerException;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.arthas.core.util.matcher.StringMatcher;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock核心增强类：实现字节码篡改、mock值缓存、清除mock
 */
public class MockEnhancer {
    // 全局mock缓存池：存储被mock的方法 -> mock配置
    public static final ConcurrentHashMap<String, MockConfig> MOCK_CACHE = new ConcurrentHashMap<>();

    /**
     * 执行mock操作
     * @param className 目标类全名
     * @param methodName 目标方法名
     * @param mockValue mock返回值
     * @param mockException mock异常信息（null则不抛异常）
     * @return 执行结果
     */
    public static String mockMethod(String className, String methodName, String mockValue, String mockException) {
        try {
            Class<?> targetClass = Class.forName(className);
            Matcher<String> classMatcher = new StringMatcher(className, StringMatcher.MATCH_MODE_EQUALS);
            Matcher<String> methodMatcher = new StringMatcher(methodName, StringMatcher.MATCH_MODE_EQUALS);

            // 缓存mock配置
            String key = className + "#" + methodName;
            MOCK_CACHE.put(key, new MockConfig(mockValue, mockException));

            // Arthas核心API：字节码增强目标类和方法
            Enhancer.enhance(classMatcher, methodMatcher, targetClass.getClassLoader(), 
                (cls, method, advice) -> {
                    // 方法执行前拦截，直接返回mock值/抛异常
                    if (mockException != null && !mockException.isEmpty()) {
                        throw new RuntimeException("Mock Exception: " + mockException);
                    }
                    return convertMockValue(mockValue, method.getReturnType());
                });

            return "✅ Mock成功！类: " + className + " 方法: " + methodName + " → mock值: " + mockValue;
        } catch (ClassNotFoundException e) {
            return "❌ Mock失败：找不到目标类 → " + className;
        } catch (EnhancerException e) {
            return "❌ Mock失败：字节码增强异常 → " + e.getMessage();
        } catch (Exception e) {
            return "❌ Mock失败：" + e.getMessage();
        }
    }

    /**
     * 清除指定方法的mock，恢复原逻辑
     */
    public static String clearMock(String className, String methodName) {
        String key = className + "#" + methodName;
        if (MOCK_CACHE.containsKey(key)) {
            MOCK_CACHE.remove(key);
            // 重新增强，恢复原方法逻辑
            try {
                Class<?> targetClass = Class.forName(className);
                Enhancer.enhance(new StringMatcher(className, StringMatcher.MATCH_MODE_EQUALS),
                        new StringMatcher(methodName, StringMatcher.MATCH_MODE_EQUALS),
                        targetClass.getClassLoader(), (cls, method, advice) -> advice.invoke());
                return "✅ 清除Mock成功！类: " + className + " 方法: " + methodName;
            } catch (Exception e) {
                return "❌ 清除Mock失败：" + e.getMessage();
            }
        } else {
            return "⚠️ 该方法未被Mock：" + className + "#" + methodName;
        }
    }

    /**
     * 清除所有mock配置
     */
    public static String clearAllMock() {
        MOCK_CACHE.clear();
        return "✅ 清除所有Mock配置成功！当前缓存池已清空";
    }

    /**
     * 类型转换：将字符串mock值转为方法返回值的实际类型
     */
    private static Object convertMockValue(String mockValue, Class<?> returnType) {
        if ("null".equalsIgnoreCase(mockValue)) {
            return null;
        }
        if (returnType == String.class) {
            return mockValue;
        }
        if (returnType == int.class || returnType == Integer.class) {
            return Integer.parseInt(mockValue);
        }
        if (returnType == long.class || returnType == Long.class) {
            return Long.parseLong(mockValue);
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.parseBoolean(mockValue);
        }
        if (returnType == double.class || returnType == Double.class) {
            return Double.parseDouble(mockValue);
        }
        return mockValue;
    }

    /**
     * Mock配置实体类：存储mock值和异常信息
     */
    public static class MockConfig {
        private String mockValue;
        private String mockException;

        public MockConfig(String mockValue, String mockException) {
            this.mockValue = mockValue;
            this.mockException = mockException;
        }

        public String getMockValue() { return mockValue; }
        public String getMockException() { return mockException; }
    }
}