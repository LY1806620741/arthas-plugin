package io.github.ly1806620741.arthas.plugin;

import java.util.ArrayList;
import java.util.List;

import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.klass100.RetransformCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.DefaultValue;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

import ognl.Ognl;
import ognl.OgnlException;

@Name("mock")
@Summary("Arthas 4.1.4 自定义Mock命令：动态mock指定类的指定方法返回值/抛出异常/修改入参，无侵入不重启")
@Description("Mock命令使用示例：\n" +
        "  1. 立即返回固定值: mock com.demo.UserService getUserById -b '{\"id\":100,\"name\":\"mock-user\"}'\n" +
        "  2. 执行后修改返回值: mock com.demo.OrderService getOrder -s 'null'\n" +
        "  3. 抛出指定异常: mock com.demo.PayService pay -e 'new RuntimeException(\"支付失败\")'\n" +
        "  4. 修改方法入参: mock com.demo.UserService updateUser -b '[0, {\"id\":1,\"name\":\"modify\"}]'\n" +
        "  5. 清除指定mock: mock com.demo.UserService getUserById --clear\n" +
        "  6. 清除全部mock: mock --clear-all\n")
public class MockCommand extends RetransformCommand {

    private String classPattern;
    private String methodPattern;
    private String express;
    private String conditionExpress;
    private String ognl;
    private boolean isBefore = false;
    private Integer sizeLimit = 10 * 1024 * 1024;
    private boolean isRegEx = false;
    private int numberOfLimit = 100;
    private boolean verbose = false;

    private static volatile List<String> mockClass = new ArrayList<String>();

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

    @Option(shortName = "M", longName = "sizeLimit")
    @Description("Upper size limit in bytes for the result (10 * 1024 * 1024 by default)")
    public void setSizeLimit(Integer sizeLimit) {
        this.sizeLimit = sizeLimit;
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

    public Integer getSizeLimit() {
        return sizeLimit;
    }

    public boolean isRegEx() {
        return isRegEx;
    }

    public int getNumberOfLimit() {
        return numberOfLimit;
    }

    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public void process(CommandProcess process) {
        try {
            Object expression = Ognl.parseExpression(ognl);
        } catch (OgnlException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // 缓存ognl

        // 增强字节

        // 管理mockClass

    }

    class OgnlMockAdvice {

    }
}