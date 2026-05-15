package io.github.ly1806620741.arthas.plugin.itapp;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.lang.reflect.Field;
import java.util.Collection;

@SpringBootApplication
public class ArthasSpringBootProxyTargetApp {

    public static void main(String[] args) {
        SpringApplication.run(ArthasSpringBootProxyTargetApp.class, args);
    }

    @org.springframework.context.annotation.Bean
    public CglibProxyTarget cglibProxyTarget() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(CglibProxyTarget.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> proxy.invokeSuper(obj, args));
        return (CglibProxyTarget) enhancer.create();
    }

    @org.springframework.context.annotation.Bean
    public InitializingBean registerToLiveBeansView(final ApplicationContext applicationContext) {
        return new InitializingBean() {
            @Override
            public void afterPropertiesSet() throws Exception {
                Class<?> liveBeansViewClass = Class.forName("org.springframework.context.support.LiveBeansView");
                Field applicationContextsField = liveBeansViewClass.getDeclaredField("applicationContexts");
                applicationContextsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Collection<Object> contexts = (Collection<Object>) applicationContextsField.get(null);
                contexts.add(applicationContext);
            }
        };
    }

    @Controller
    static class SampleController {
        private final CglibProxyTarget cglibProxyTarget;

        SampleController(CglibProxyTarget cglibProxyTarget) {
            this.cglibProxyTarget = cglibProxyTarget;
        }

        @GetMapping("/sample")
        @ResponseBody
        public ResponseEntity<String> sample() {
            return new ResponseEntity<String>("sample", HttpStatus.OK);
        }

        @GetMapping("/mock/cglib/value")
        @ResponseBody
        public ResponseEntity<String> cglibValue() {
            return new ResponseEntity<String>(cglibProxyTarget.value(), HttpStatus.OK);
        }

        @GetMapping("/mock/cglib/debug-value")
        @ResponseBody
        public ResponseEntity<String> cglibDebugValue() {
            try {
                return new ResponseEntity<String>(cglibProxyTarget.value(), HttpStatus.OK);
            } catch (Throwable throwable) {
                String message = throwable.getClass().getName() + ":" + String.valueOf(throwable.getMessage());
                return new ResponseEntity<String>(message, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        @GetMapping("/mock/cglib/class-name")
        @ResponseBody
        public ResponseEntity<String> cglibClassName() {
            return new ResponseEntity<String>(cglibProxyTarget.getClass().getName(), HttpStatus.OK);
        }
    }

    public static class CglibProxyTarget {
        public String value() {
            return "origin";
        }
    }
}



