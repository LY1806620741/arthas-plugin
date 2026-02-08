package io.github.ly1806620741.arthas;

import org.benf.cfr.reader.util.Optional;

import com.taobao.arthas.core.advisor.ArthasMethod;

public class OgnlContext {

    private final ClassLoader loader;
    private final Class<?> clazz;
    private final ArthasMethod method;
    private final Object target;
    private final Object[] params;
    private Throwable throwExp;

    /**
     * for finish
     *
     * @param loader    类加载器
     * @param clazz     类
     * @param method    方法
     * @param target    目标类
     * @param params    调用参数
     * @param returnObj 返回值
     * @param throwExp  抛出异常
     * @param access    进入场景
     */
    private OgnlContext(
        ClassLoader loader,
        Class<?> clazz,
        ArthasMethod method,
        Object target,
        Object[] params) {
    this.loader = loader;
    this.clazz = clazz;
    this.method = method;
    this.target = target;
    this.params = params;
}

    public Object originReturnObj = Optional.empty();

    public Boolean skip = true;

    public Object returnObj = Optional.empty();

    public static OgnlContext init(ClassLoader loader,
            Class<?> clazz,
            ArthasMethod method,
            Object target,
            Object[] params,
            Object returnObj) {
        OgnlContext ognlContext = new OgnlContext(loader,clazz,method,target,params);
        return ognlContext;
    }
}