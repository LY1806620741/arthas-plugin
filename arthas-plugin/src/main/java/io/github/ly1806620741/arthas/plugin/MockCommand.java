package io.github.ly1806620741.arthas.plugin;

import java.util.Arrays;

import com.taobao.arthas.core.GlobalOptions;
import com.taobao.arthas.core.advisor.AdviceListener;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.monitor200.EnhancerCommand;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.matcher.Matcher;
import com.taobao.arthas.core.view.ObjectView;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.DefaultValue;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

@Name("mock")
@Summary("Arthas 4.1.4 自定义Mock命令：动态mock指定类的指定方法返回值/抛出异常/修改入参，无侵入不重启")
@Description(
        "Mock命令使用示例：\n"+
        "  1. 立即返回固定值: mock com.demo.UserService getUserById -b '{\"id\":100,\"name\":\"mock-user\"}'\n"+
        "  2. 执行后修改返回值: mock com.demo.OrderService getOrder -s 'null'\n"+
        "  3. 抛出指定异常: mock com.demo.PayService pay -e 'new RuntimeException(\"支付失败\")'\n"+
        "  4. 修改方法入参: mock com.demo.UserService updateUser -b '[0, {\"id\":1,\"name\":\"modify\"}]'\n"+
        "  5. 清除指定mock: mock com.demo.UserService getUserById --clear\n"+
        "  6. 清除全部mock: mock --clear-all\n"
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
    private boolean verbose = false;

    @Argument(index = 0, argName = "class-pattern")
    @Description("The full qualified class name you want to mock")
    public void setClassPattern(String classPattern) {
        this.classPattern = StringUtils.normalizeClassName(classPattern);
    }

    @Argument(index = 1, argName = "method-pattern")
    @Description("The method name you want to mock")
    public void setMethodPattern(String methodPattern) {
        this.methodPattern = methodPattern;
    }

    @Argument(index = 2, argName = "express", required = false)
    @DefaultValue("{params, target, returnObj}")
    @Description("The mock express, written by ognl. \n" + Constants.EXPRESS_EXAMPLES)
    public void setExpress(String express) {
        this.express = express;
    }

    @Argument(index = 3, argName = "condition-express", required = false)
    @Description(Constants.CONDITION_EXPRESS)
    public void setConditionExpress(String conditionExpress) {
        this.conditionExpress = conditionExpress;
    }

    @Option(shortName = "b", longName = "before", flag = true)
    @Description("Mock before method invocation (修改入参/立即返回)")
    public void setBefore(boolean before) {
        isBefore = before;
    }

    @Option(shortName = "f", longName = "finish", flag = true)
    @Description("Mock after method invocation finish")
    public void setFinish(boolean finish) {
        isFinish = finish;
    }

    @Option(shortName = "e", longName = "exception", flag = true)
    @Description("Mock after throw exception (篡改异常/返回正常值)")
    public void setException(boolean exception) {
        isException = exception;
    }

    @Option(shortName = "s", longName = "success", flag = true)
    @Description("Mock after successful invocation (修改返回值/抛异常)")
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

    @Option(shortName = "v", longName = "verbose", flag = true)
    @Description("Enable verbose output")
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    private String mockReturn;
    private String mockThrow;
    private boolean returnImmediately = false;
    private boolean throwImmediately = false;
    private boolean modifyArg = false;
    private boolean modifyReturn = false;
    private boolean throwAfterSuccess = false;
    private boolean modifyThrowable = false;
    private boolean returnAfterThrow = false;
    private Long timeout;
    private boolean clear = false;
    private boolean clearAll = false;

    @Option(longName = "return", argName = "mock-value")
    @Description("Mock return value")
    public void setMockReturn(String mockReturn) {
        this.mockReturn = mockReturn;
    }

    @Option(longName = "throw", argName = "exception-msg")
    @Description("Mock throw exception")
    public void setMockThrow(String mockThrow) {
        this.mockThrow = mockThrow;
    }

    @Option(longName = "return-immediately", flag = true)
    @Description("Return mock value immediately, skip method execute")
    public void setReturnImmediately(boolean returnImmediately) {
        this.returnImmediately = returnImmediately;
    }

    @Option(longName = "throw-immediately", flag = true)
    @Description("Throw exception immediately, skip method execute")
    public void setThrowImmediately(boolean throwImmediately) {
        this.throwImmediately = throwImmediately;
    }

    @Option(longName = "modify-arg", flag = true)
    @Description("Allow modify method arguments")
    public void setModifyArg(boolean modifyArg) {
        this.modifyArg = modifyArg;
    }

    @Option(longName = "modify-return", flag = true)
    @Description("Modify method return value after execute")
    public void setModifyReturn(boolean modifyReturn) {
        this.modifyReturn = modifyReturn;
    }

    @Option(longName = "throw-after-success", flag = true)
    @Description("Throw exception after method success")
    public void setThrowAfterSuccess(boolean throwAfterSuccess) {
        this.throwAfterSuccess = throwAfterSuccess;
    }

    @Option(longName = "modify-throwable", flag = true)
    @Description("Modify the throwable after method throw exception")
    public void setModifyThrowable(boolean modifyThrowable) {
        this.modifyThrowable = modifyThrowable;
    }

    @Option(longName = "return-after-throw", flag = true)
    @Description("Return normal value after method throw exception")
    public void setReturnAfterThrow(boolean returnAfterThrow) {
        this.returnAfterThrow = returnAfterThrow;
    }

    @Option(longName = "timeout", argName = "ms")
    @Description("Set method execution timeout")
    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    @Option(longName = "clear", flag = true)
    @Description("Clear the mock configuration")
    public void setClear(boolean clear) {
        this.clear = clear;
    }

    @Option(longName = "clear-all", flag = true)
    @Description("Clear all mock configurations")
    public void setClearAll(boolean clearAll) {
        this.clearAll = clearAll;
    }

    public String getMockReturn() { return mockReturn; }
    public String getMockThrow() { return mockThrow; }
    public boolean isReturnImmediately() { return returnImmediately; }
    public boolean isThrowImmediately() { return throwImmediately; }
    public boolean isModifyArg() { return modifyArg; }
    public boolean isModifyReturn() { return modifyReturn; }
    public boolean isThrowAfterSuccess() { return throwAfterSuccess; }
    public boolean isModifyThrowable() { return modifyThrowable; }
    public boolean isReturnAfterThrow() { return returnAfterThrow; }
    @Override
    public Long getTimeout() { return timeout; }
    public boolean isClear() { return clear; }
    public boolean isClearAll() { return clearAll; }

    public String getClassPattern() { return classPattern; }
    public String getMethodPattern() { return methodPattern; }
    public String getExpress() { return express; }
    public String getConditionExpress() { return conditionExpress; }
    public boolean isBefore() { return isBefore; }
    public boolean isFinish() { return isFinish; }
    public boolean isException() { return isException; }
    public boolean isSuccess() { return isSuccess; }
    public Integer getExpand() { return expand; }
    public Integer getSizeLimit() { return sizeLimit; }
    public boolean isRegEx() { return isRegEx; }
    public int getNumberOfLimit() { return numberOfLimit; }
    public boolean isVerbose() { return verbose; }

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
        CompletionUtils.complete(completion, Arrays.asList(Constants.EXPRESS_EXAMPLES));
    }
}