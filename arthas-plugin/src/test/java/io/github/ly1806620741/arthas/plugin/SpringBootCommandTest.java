package io.github.ly1806620741.arthas.plugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.taobao.arthas.core.shell.command.CommandProcess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@SpringBootTest(classes = SpringBootCommandTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringBootCommandTest {

    private static final String AES = "AES/CBC/PKCS5Padding";
    private static HttpServer targetServer;
    private static int targetPort;

    @LocalServerPort
    private int appPort;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeAll
    static void startTargetServer() throws Exception {
        targetServer = HttpServer.create(new InetSocketAddress(0), 0);
        targetServer.createContext("/", new EchoHandler());
        targetServer.start();
        targetPort = targetServer.getAddress().getPort();
    }

    @AfterAll
    static void stopTargetServer() {
        if (targetServer != null) {
            targetServer.stop(0);
        }
    }

    @BeforeEach
    void registerApplicationContext() throws Exception {
        Class<?> liveBeansViewClass = Class.forName("org.springframework.context.support.LiveBeansView");
        Field applicationContextsField = liveBeansViewClass.getDeclaredField("applicationContexts");
        applicationContextsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Collection<Object> contexts = (Collection<Object>) applicationContextsField.get(null);
        contexts.add(applicationContext);
    }

    @Test
    @DisplayName("springboot --proxy-http 可注入转发路由")
    void proxyHttpShouldInjectRouteAndForwardTraffic() throws Exception {
        SpringBootCommand command = new SpringBootCommand();
        command.setProxyHttp(true);
        command.setRoutePattern("/arthas/plain/**");
        command.setTargetPort(targetPort);

        CommandProcess process = Mockito.mock(CommandProcess.class);
        command.process(process);
        Mockito.verify(process).end(Mockito.eq(0), Mockito.contains("Route injected successfully: /arthas/plain/**"));

        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + appPort + "/arthas/plain/api/test?foo=bar").openConnection();
        connection.setRequestMethod("GET");
        Assertions.assertEquals(HttpStatus.OK.value(), connection.getResponseCode());
        String body = new String(readAllBytes(connection.getInputStream()), StandardCharsets.UTF_8);
        Assertions.assertTrue(body.contains("GET /api/test?foo=bar"), body);
    }

    @Test
    @DisplayName("springboot --proxy-http --encrypt 可对请求响应进行 AES 转发")
    void proxyHttpShouldSupportEncryptedPayload() throws Exception {
        SpringBootCommand command = new SpringBootCommand();
        command.setProxyHttp(true);
        command.setRoutePattern("/arthas/secure/**");
        command.setTargetPort(targetPort);
        command.setEncrypt(true);
        command.setEncryptKey("arthas");
        command.setEncryptIv("0123456789abcdef");

        CommandProcess process = Mockito.mock(CommandProcess.class);
        command.process(process);
        Mockito.verify(process).end(Mockito.eq(0), Mockito.contains("Route injected successfully: /arthas/secure/**"));

        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + appPort + "/arthas/secure/echo").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        byte[] encryptedRequest = encrypt("payload".getBytes(StandardCharsets.UTF_8));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(encryptedRequest);
        }
        Assertions.assertEquals(HttpStatus.OK.value(), connection.getResponseCode());
        Assertions.assertEquals(AES, connection.getHeaderField("X-Arthas-Proxy-Encrypted"));
        byte[] encryptedResponse = readAllBytes(connection.getInputStream());
        String plainResponse = new String(decrypt(encryptedResponse), StandardCharsets.UTF_8);
        Assertions.assertTrue(plainResponse.contains("POST /echo payload"), plainResponse);
    }

    private static byte[] encrypt(byte[] body) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(normalize("arthas"), "AES"),
                new IvParameterSpec(normalize("0123456789abcdef")));
        return Base64.getEncoder().encode(cipher.doFinal(body));
    }

    private static byte[] decrypt(byte[] body) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(normalize("arthas"), "AES"),
                new IvParameterSpec(normalize("0123456789abcdef")));
        return cipher.doFinal(Base64.getDecoder().decode(new String(body, StandardCharsets.UTF_8).trim()));
    }

    private static byte[] normalize(String value) {
        return Arrays.copyOf(value.getBytes(StandardCharsets.UTF_8), 16);
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    @SpringBootApplication
    static class TestApplication {
    }

    @Controller
    static class SampleController {
        @GetMapping("/sample")
        public org.springframework.http.ResponseEntity<String> sample() {
            return new org.springframework.http.ResponseEntity<String>("sample", HttpStatus.OK);
        }
    }

    static class EchoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
            String query = exchange.getRequestURI().getRawQuery();
            String payload = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + (query == null ? "" : "?" + query)
                    + (body.isEmpty() ? "" : " " + body);
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }
    }
}


