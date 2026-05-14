package io.github.ly1806620741.arthas.plugin.itapp;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Field;
import java.util.Collection;

@SpringBootApplication
public class ArthasSpringBootProxyTargetApp {

    public static void main(String[] args) {
        SpringApplication.run(ArthasSpringBootProxyTargetApp.class, args);
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
        @GetMapping("/sample")
        public ResponseEntity<String> sample() {
            return new ResponseEntity<String>("sample", HttpStatus.OK);
        }
    }
}



