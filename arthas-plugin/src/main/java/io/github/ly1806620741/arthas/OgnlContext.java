package io.github.ly1806620741.arthas;

import com.taobao.arthas.core.advisor.ArthasMethod;

import java.util.Optional;

public class OgnlContext {

    private final ClassLoader loader;
    private final Class<?> clazz;
    private final ArthasMethod method;
    private final Object target;
    private final Object[] params;
    private Throwable throwExp;

    /**
     * OGNL runtime context for mock execution.
     *
     * @param loader    类加载器
     * @param clazz     类
     * @param method    方法
     * @param target    目标类
     * @param params    调用参数
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

    private Object json = Optional.empty();

    public static OgnlContext init(ClassLoader loader,
            Class<?> clazz,
            ArthasMethod method,
            Object target,
            Object[] params,
            Object returnObj) {
        OgnlContext ognlContext = new OgnlContext(loader,clazz,method,target,params);
        ognlContext.originReturnObj = returnObj == null ? Optional.empty() : returnObj;
        ognlContext.returnObj = returnObj == null ? Optional.empty() : returnObj;
        return ognlContext;
    }

    public ClassLoader getLoader() {
        return loader;
    }

    public Class<?> getClazz() {
        return clazz;
    }

    public ArthasMethod getMethod() {
        return method;
    }

    public Object getTarget() {
        return target;
    }

    public Object[] getParams() {
        return params;
    }

    public Throwable getThrowExp() {
        return throwExp;
    }

    public void setThrowExp(Throwable throwExp) {
        this.throwExp = throwExp;
    }

    public void setOriginReturnObj(Object originReturnObj) {
        this.originReturnObj = originReturnObj == null ? Optional.empty() : originReturnObj;
    }

    public void setReturnObj(Object returnObj) {
        this.returnObj = returnObj == null ? Optional.empty() : returnObj;
    }

    public Object getJson() {
        return json;
    }

    public void setJson(Object json) {
        this.json = json == null ? Optional.empty() : json;
    }
}