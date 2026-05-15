package io.github.ly1806620741.arthas.plugin;

import com.taobao.arthas.core.command.model.EnhancerModelFactory;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.affect.EnhancerAffect;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

@Name("springboot")
@Summary("Spring Boot toolkit for Arthas, including HTTP proxy route injection")
@Description("Examples:\n"
        + "  springboot --proxy-http\n"
        + "  springboot --proxy-http --route /arthas/** --target-port 8563\n"
        + "  springboot --proxy-http --encrypt --encrypt-key arthas --encrypt-iv 0123456789abcdef\n")
public class SpringBootCommand extends AnnotatedCommand {

    private boolean proxyHttp;
    private String routePattern = "/arthas/**";
    private String targetHost = "127.0.0.1";
    private int targetPort = 8563;
    private boolean encrypt;
    private String encryptKey = "arthas";
    private String encryptIv = "0123456789abcdef";

    @Option(longName = "proxy-http", flag = true)
    @Description("Inject a Spring MVC route that forwards /arthas/** to Arthas HTTP port")
    public void setProxyHttp(boolean proxyHttp) {
        this.proxyHttp = proxyHttp;
    }

    @Option(longName = "route")
    @Description("Injected Spring route pattern, default /arthas/**")
    public void setRoutePattern(String routePattern) {
        this.routePattern = routePattern;
    }

    @Option(longName = "target-host")
    @Description("Forward target host, default 127.0.0.1")
    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    @Option(longName = "target-port")
    @Description("Forward target port, default 8563")
    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    @Option(longName = "encrypt", flag = true)
    @Description("Encrypt proxied payload with AES/CBC/PKCS5Padding")
    public void setEncrypt(boolean encrypt) {
        this.encrypt = encrypt;
    }

    @Option(longName = "encrypt-key")
    @Description("AES key used when --encrypt is enabled, default arthas")
    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey;
    }

    @Option(longName = "encrypt-iv")
    @Description("AES iv used when --encrypt is enabled, default 0123456789abcdef")
    public void setEncryptIv(String encryptIv) {
        this.encryptIv = encryptIv;
    }

    @Override
    public void process(CommandProcess process) {
        if (!proxyHttp) {
            process.end(-1, "No springboot action specified. Use --proxy-http.");
            return;
        }
        try {
            SpringHttpProxyInstaller.SpringProxyConfig config = new SpringHttpProxyInstaller.SpringProxyConfig(
                    routePattern, targetHost, targetPort, encrypt, encryptKey, encryptIv);
            int installed = SpringHttpProxyInstaller.install(config,
                    process.session() == null ? null : process.session().getInstrumentation());
            if (installed <= 0) {
                process.end(-1, "No Spring Boot controllers found in current JVM.");
                return;
            }
            String message = "Route injected successfully: " + config.getRoutePattern() + " -> "
                    + config.getTargetHost() + ":" + config.getTargetPort();
            process.appendResult(EnhancerModelFactory.create(new EnhancerAffect(), true, message));
            process.end(0, message);
        } catch (Throwable e) {
            process.end(-1, "springboot command failed: " + e.getMessage());
        }
    }
}

