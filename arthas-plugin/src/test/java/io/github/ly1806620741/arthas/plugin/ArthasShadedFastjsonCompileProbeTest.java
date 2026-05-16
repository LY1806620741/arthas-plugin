package io.github.ly1806620741.arthas.plugin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ArthasShadedFastjsonCompileProbeTest {

    @Test
    void shadedFastjsonShouldNotBeResolvableOnCurrentTestClasspath() {
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.alibaba.arthas.deps.com.alibaba.fastjson2.JSON"));
    }
}


