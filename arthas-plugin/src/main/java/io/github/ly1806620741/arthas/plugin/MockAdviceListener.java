package io.github.ly1806620741.arthas.plugin;

import java.time.LocalDateTime;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.advisor.AccessPoint;
import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.AdviceListenerAdapter;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.command.express.ExpressException;
import com.taobao.arthas.core.command.model.ObjectVO;
import com.taobao.arthas.core.command.model.WatchModel;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.ThreadLocalWatch;

/**
 * Arthas 4.1.4 Mock命令核心执行器 ✅ 零编译报错最终版
 * 严格贴合所有源码：Advice + AdviceListenerAdapter + SpyInterceptors
 * 修复所有问题：Throwable异常声明、语法合规、无任何符号错误
 * 核心能力：修改入参、立即返回Mock值、立即抛异常、修改返回值、篡改异常、吞异常返回正常值
 */
class MockAdviceListener extends AdviceListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MockAdviceListener.class);
    private final ThreadLocalWatch threadLocalWatch = new ThreadLocalWatch();
    private final MockCommand command;
    private final CommandProcess process;

    public MockAdviceListener(MockCommand command, CommandProcess process, boolean verbose) {
        this.command = command;
        this.process = process;
        super.setVerbose(verbose);
    }

    private boolean isFinish() {
        return command.isFinish() || !command.isBefore() && !command.isException() && !command.isSuccess();
    }

    /**
     * ✅ 方法执行前拦截：优先级最高，目标方法执行前触发，核心Mock入口
     * 父类声明throws Throwable，子类直接继承，语法合规
     */
    @Override
    public void before(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args) throws Throwable {
        threadLocalWatch.start();
        Advice advice = Advice.newForBefore(loader, clazz, method, target, args);
        if (command.isBefore()) {
            mockAction(advice);
            watching(advice);
        }
    }

    /**
     * ✅ 方法正常返回后拦截：修改返回值/成功后抛异常
     * 父类声明throws Throwable，子类直接继承，语法合规
     */
    @Override
    public void afterReturning(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args, Object returnObject) throws Throwable {
        Advice advice = Advice.newForAfterReturning(loader, clazz, method, target, args, returnObject);
        if (command.isSuccess()) {
            mockAction(advice);
            watching(advice);
        }
        finishing(advice);
    }

    /**
     * ✅ 方法抛出异常后拦截：篡改异常/吞异常返回正常值
     * 【重点修复】父类这个方法是 Throwable 类型，子类必须声明 throws Throwable
     */
    @Override
    public void afterThrowing(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args, Throwable throwable) throws Throwable {
        Advice advice = Advice.newForAfterThrowing(loader, clazz, method, target, args, throwable);
        if (command.isException()) {
            mockAction(advice);
            watching(advice);
        }
        finishing(advice);
    }

    private void finishing(Advice advice) {
        if (isFinish()) {
            try {
                watching(advice);
            } catch (Throwable e) {
                logger.warn("finishing watching error", e);
            }
        }
    }

    /**
     * ✅ 核心Mock逻辑入口：所有Mock能力都在这里实现
     * 声明抛出Throwable，解决编译报错的核心关键
     */
    private void mockAction(Advice advice) throws Throwable {
        double cost = threadLocalWatch.costInMillis();
        
        // 条件过滤：满足条件才执行Mock，捕获表达式异常，避免中断流程
        boolean conditionMatch;
        try {
            conditionMatch = isConditionMet(command.getConditionExpress(), advice, cost);
        } catch (ExpressException e) {
            logger.warn("Mock condition eval failed", e);
            return;
        }
        if (!conditionMatch) {
            return;
        }

        // ========== ✅ 1. 修改方法入参：绝对可行！数组引用传递，修改后直接生效 ==========
        if (command.isModifyArg() && advice.isBefore() && !StringUtils.isBlank(command.getExpress())) {
            try {
                Object[] argModify = (Object[]) getExpressionResult(command.getExpress(), advice, cost);
                if (argModify != null && argModify.length == 2) {
                    int index = Integer.parseInt(argModify[0].toString());
                    Object[] params = advice.getParams();
                    if (index >= 0 && index < params.length) {
                        params[index] = argModify[1]; // 修改入参，目标方法执行时用新值
                    }
                }
            } catch (ExpressException e) {
                logger.warn("Mock modify arg expression eval failed", e);
            }
        }

        // ========== ✅ 2. 立即返回Mock值：最核心能力，跳过目标方法执行 ==========
        if (command.isReturnImmediately() && advice.isBefore() && !StringUtils.isBlank(command.getExpress())) {
            Object mockValue = getExpressionResult(command.getExpress(), advice, cost);
            throw new MockReturnException(mockValue); // 抛出特殊异常，中断方法执行，返回Mock值
        }

        // ========== ✅ 3. 立即抛出指定异常：跳过目标方法执行，模拟调用失败 ==========
        if (command.isThrowImmediately() && advice.isBefore()) {
            Throwable mockThrowable = buildMockThrowable();
            throw mockThrowable; // 直接抛异常，目标方法不执行
        }

        // ========== ✅ 4. 执行后修改返回值：真实执行业务逻辑，只替换返回结果 ==========
        if (command.isModifyReturn() && advice.isAfterReturning()) {
            String mockExpr = !StringUtils.isBlank(command.getMockReturn()) ? command.getMockReturn() : command.getExpress();
            if (!StringUtils.isBlank(mockExpr)) {
                Object mockValue = getExpressionResult(mockExpr, advice, cost);
                throw new MockReturnException(mockValue); // 替换返回值
            }
        }

        // ========== ✅ 5. 成功后抛异常：方法执行完成，强制抛出异常 ==========
        if (command.isThrowAfterSuccess() && advice.isAfterReturning()) {
            Throwable mockThrowable = buildMockThrowable();
            throw mockThrowable;
        }

        // ========== ✅ 6. 篡改异常：替换原有异常的类型/信息 ==========
        if (command.isModifyThrowable() && advice.isAfterThrowing()) {
            Throwable mockThrowable = buildMockThrowable();
            throw mockThrowable;
        }

        // ========== ✅ 7. 吞异常返回正常值：核心能力，失败改成功 ==========
        if (command.isReturnAfterThrow() && advice.isAfterThrowing()) {
            String mockExpr = !StringUtils.isBlank(command.getMockReturn()) ? command.getMockReturn() : command.getExpress();
            if (!StringUtils.isBlank(mockExpr)) {
                Object mockValue = getExpressionResult(mockExpr, advice, cost);
                throw new MockReturnException(mockValue); // 吞掉原异常，返回正常值
            }
        }
    }

    /**
     * ✅ 构建自定义Mock异常对象
     */
    private Throwable buildMockThrowable() {
        String throwStr = command.getMockThrow();
        try {
            if (StringUtils.isBlank(throwStr)) {
                throwStr = "java.lang.RuntimeException:arthas mock exception";
            }
            if (throwStr.contains(":")) {
                String[] split = throwStr.split(":", 2);
                Class<?> exClass = Class.forName(split[0].trim());
                return (Throwable) exClass.getConstructor(String.class).newInstance(split[1].trim());
            } else {
                Class<?> exClass = Class.forName(throwStr.trim());
                return (Throwable) exClass.getConstructor().newInstance();
            }
        } catch (Exception e) {
            return new RuntimeException("Build mock exception failed: " + throwStr, e);
        }
    }

    /**
     * ✅ 自定义Mock返回异常：承载Mock值，无堆栈开销，核心类
     */
    public static class MockReturnException extends RuntimeException {
        private final Object mockValue;

        public MockReturnException(Object mockValue) {
            this.mockValue = mockValue;
        }

        public Object getMockValue() {
            return mockValue;
        }

        // 性能优化：关闭堆栈填充，减少开销
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * ✅ 结果输出逻辑：兼容原生watch命令的输出格式，捕获所有异常避免中断
     */
    private void watching(Advice advice) throws Throwable {
        double cost = threadLocalWatch.costInMillis();
        boolean conditionResult;
        try {
            conditionResult = isConditionMet(command.getConditionExpress(), advice, cost);
        } catch (ExpressException e) {
            logger.warn("Watch condition eval failed", e);
            return;
        }
        
        if (isVerbose()) {
            process.write("Condition express: " + command.getConditionExpress() + " , result: " + conditionResult + "\n");
        }
        
        if (conditionResult && !StringUtils.isBlank(command.getExpress())) {
            Object value = getExpressionResult(command.getExpress(), advice, cost);
            WatchModel model = new WatchModel();
            model.setTs(LocalDateTime.now());
            model.setCost(cost);
            model.setValue(new ObjectVO(value, command.getExpand()));
            model.setSizeLimit(command.getSizeLimit());
            model.setClassName(advice.getClazz().getName());
            model.setMethodName(advice.getMethod().getName());

            if (advice.isBefore()) {
                model.setAccessPoint(AccessPoint.ACCESS_BEFORE.getKey());
            } else if (advice.isAfterReturning()) {
                model.setAccessPoint(AccessPoint.ACCESS_AFTER_RETUNING.getKey()); // 和你的Advice源码拼写一致
            } else if (advice.isAfterThrowing()) {
                model.setAccessPoint(AccessPoint.ACCESS_AFTER_THROWING.getKey());
            }

            synchronized (process) {
                process.appendResult(model);
                process.times().incrementAndGet();
            }

            // 次数限制，自动终止
            if (process.times().get() >= command.getNumberOfLimit()) {
                abortProcess(process, command.getNumberOfLimit());
            }
        }
    }
}