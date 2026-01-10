package io.github.ly1806620741.arthas.plugin;

import com.taobao.arthas.core.GlobalOptions;
import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.command.monitor200.EnhancerCommand;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.arthas.core.view.ObjectView;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.DefaultValue;
import com.taobao.middleware.cli.annotations.Description;
// import com.taobao.arthas.core.command.monitor200.WatchAdviceListener;

import java.util.Arrays;
import java.util.List;

/**
 * Arthas 4.1.4 官方标准自定义命令 ✅
 * 核心变更：实现 AnnotatedCommand 接口，使用 @Command 注解替代原有的 @CommandMapping
 * 命令名：mock ，功能完全不变：动态mock方法返回值/异常/清除mock
 */
@Name("mock")
@Summary("Arthas 4.1.4 自定义Mock命令：动态mock指定类的指定方法返回值/抛出异常")
@Description(
                "mock com.demo.service.UserService getUserById 1001\n"+
                "mock com.demo.service.OrderService getOrder null\n"+
                "mock com.demo.service.PayService pay -e 支付失败:余额不足\n"+
                "mock --clear com.demo.service.UserService getUserById\n"+
                "mock --clear-all\n"
)
public class MockCommand extends EnhancerCommand {

    private String classPattern;
    private String methodPattern;
    private String express;
    private String conditionExpress;
    private boolean isBefore = false;
    private boolean isFinish = false;
    private boolean isException = false;
    private boolean isSuccess = false;
    private Integer expand = 1;
    private Integer sizeLimit = 10 * 1024 * 1024;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;

    @Argument(index = 0, argName = "class-pattern")
    @Description("The full qualified class name you want to watch")
    public void setClassPattern(String classPattern) {
        this.classPattern = StringUtils.normalizeClassName(classPattern);
    }

    @Argument(index = 1, argName = "method-pattern")
    @Description("The method name you want to watch")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    @Argument(index = 2, argName = "express", required = false)
    @DefaultValue("{params, target, returnObj}")
    @Description("The content you want to watch, written by ognl. Default value is '{params, target, returnObj}'\n" + Constants.EXPRESS_EXAMPLES)
    public void setExpress(String express) {
        this.express = express;
    }

    @Argument(index = 3, argName = "condition-express", required = false)
    @Description(Constants.CONDITION_EXPRESS)
    public void setConditionExpress(String conditionExpress) {
        this.conditionExpress = conditionExpress;
    }

    @Option(shortName = "b", longName = "before", flag = true)
    @Description("Watch before invocation")
    public void setBefore(boolean before) {
        isBefore = before;
    }

    @Option(shortName = "f", longName = "finish", flag = true)
    @Description("Watch after invocation, enable by default")
    public void setFinish(boolean finish) {
        isFinish = finish;
    }

    @Option(shortName = "e", longName = "exception", flag = true)
    @Description("Watch after throw exception")
    public void setException(boolean exception) {
        isException = exception;
    }

    @Option(shortName = "s", longName = "success", flag = true)
    @Description("Watch after successful invocation")
    public void setSuccess(boolean success) {
        isSuccess = success;
    }

    @Option(shortName = "M", longName = "sizeLimit")
    @Description("Upper size limit in bytes for the result (10 * 1024 * 1024 by default)")
    public void setSizeLimit(Integer sizeLimit) {
        this.sizeLimit = sizeLimit;
    }

    @Option(shortName = "x", longName = "expand")
    @Description("Expand level of object (1 by default), the max value is " + ObjectView.MAX_DEEP)
    public void setExpand(Integer expand) {
        this.expand = expand;
    }

    @Option(shortName = "E", longName = "regex", flag = true)
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "n", longName = "limits")
    @Description("Threshold of execution times")
    public void setNumberOfLimit(int numberOfLimit) {
        this.numberOfLimit = numberOfLimit;
    }

     public String getClassPattern() {
        return classPattern;
    }

    public String getMethodPattern() {
        return methodPattern;
    }

    public String getExpress() {
        return express;
    }

    public String getConditionExpress() {
        return conditionExpress;
    }

    public boolean isBefore() {
        return isBefore;
    }

    public boolean isFinish() {
        return isFinish;
    }

    public boolean isException() {
        return isException;
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    public Integer getExpand() {
        return expand;
    }

    public Integer getSizeLimit() {
        return sizeLimit;
    }

    public boolean isRegEx() {
        return isRegEx;
    }

    public int getNumberOfLimit() {
        return numberOfLimit;
    }

    @Override
    protected Matcher getClassNameMatcher() {
        if (classNameMatcher == null) {
            classNameMatcher = SearchUtils.classNameMatcher(getClassPattern(), isRegEx());
        }
        return classNameMatcher;
    }

    @Override
    protected Matcher getClassNameExcludeMatcher() {
        if (classNameExcludeMatcher == null && getExcludeClassPattern() != null) {
            classNameExcludeMatcher = SearchUtils.classNameMatcher(getExcludeClassPattern(), isRegEx());
        }
        return classNameExcludeMatcher;
    }

    @Override
    protected Matcher getMethodNameMatcher() {
        if (methodNameMatcher == null) {
            methodNameMatcher = SearchUtils.classNameMatcher(getMethodPattern(), isRegEx());
        }
        return methodNameMatcher;
    }

    @Override
    protected AdviceListener getAdviceListener(CommandProcess process) {
        return new MockAdviceListener(this, process, GlobalOptions.verbose || this.verbose);
    }

    @Override
    protected void completeArgument3(Completion completion) {
        CompletionUtils.complete(completion, Arrays.asList(EXPRESS_EXAMPLES));
    }


    // @Override
    // public void process(CommandProcess process) {
    //     try {
    //         String result = handleMockCommand(process);
    //         process.write(result + "\n");
    //     } catch (Exception e) {
    //         process.write("❌ Mock命令执行异常：" + e.getMessage() + "\n");
    //     } finally {
    //         process.end(); // ✅ 必须调用end()，否则Arthas控制台阻塞，核心规范
    //     }
    // }

    // /**
    //  * 核心逻辑处理，和之前功能完全一致
    //  */
    // private String handleMockCommand(CommandProcess process) {
    //     if (clearAll) {
    //         return MockEnhancer.clearAllMock();
    //     }
        
    //     if (clear) {
    //         if (args == null || args.size() < 2) {
    //             return "❌ 清除mock参数不足！示例：mock --clear com.demo.UserService getUserById";
    //         }
    //         String className = args.get(0);
    //         String methodName = args.get(1);
    //         return MockEnhancer.clearMock(className, methodName);
    //     }

    //     if (exceptionMsg != null && args != null && args.size() >= 2) {
    //         String className = args.get(0);
    //         String methodName = args.get(1);
    //         return MockEnhancer.mockMethod(className, methodName, null, exceptionMsg);
    //     }

    //     if (args == null || args.size() < 3) {
    //         return "❌ Mock参数不足！示例：mock com.demo.UserService getUserById 1001 \n输入 mock -h 查看帮助";
    //     }

    //     String className = args.get(0);
    //     String methodName = args.get(1);
    //     String mockValue = args.get(2);
    //     return MockEnhancer.mockMethod(className, methodName, mockValue, null);
    // }
}