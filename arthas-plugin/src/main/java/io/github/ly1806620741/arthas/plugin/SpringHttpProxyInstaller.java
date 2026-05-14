package io.github.ly1806620741.arthas.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SpringHttpProxyInstaller {

	private static final String LIVE_BEANS_VIEW_CLASS = "org.springframework.context.support.LiveBeansView";
	private static final String APPLICATION_CONTEXTS_FIELD = "applicationContexts";
	private static final String HANDLER_MAPPING_CLASS =
			"org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping";
	private static final String REQUEST_MAPPING_INFO_CLASS =
			"org.springframework.web.servlet.mvc.method.RequestMappingInfo";
	private static final String BUILDER_CONFIGURATION_CLASS =
			"org.springframework.web.servlet.mvc.method.RequestMappingInfo$BuilderConfiguration";
	private static final String HTTP_REQUEST_HANDLER_CLASS = "org.springframework.web.HttpRequestHandler";
	private static final String AES = "AES/CBC/PKCS5Padding";
	private static final String ENCRYPTION_HEADER = "X-Arthas-Proxy-Encrypted";
	private static final List<String> HOP_BY_HOP_HEADERS = Arrays.asList(
			"connection", "content-length", "host", "transfer-encoding");
	private static final Map<String, RouteRegistration> ROUTES = new ConcurrentHashMap<String, RouteRegistration>();

	private SpringHttpProxyInstaller() {
	}

	static int install(SpringProxyConfig config) throws Exception {
		int installed = 0;
		for (Object applicationContext : findApplicationContexts()) {
			Object handlerMapping = findHandlerMappingWithControllers(applicationContext);
			if (handlerMapping == null) {
				continue;
			}
			registerRoute(applicationContext, handlerMapping, config);
			installed++;
		}
		return installed;
	}

	private static Collection<Object> findApplicationContexts() throws Exception {
		Class<?> liveBeansViewClass = Class.forName(LIVE_BEANS_VIEW_CLASS, false, ClassLoader.getSystemClassLoader());
		Field applicationContextsField = liveBeansViewClass.getDeclaredField(APPLICATION_CONTEXTS_FIELD);
		applicationContextsField.setAccessible(true);
		Object value = applicationContextsField.get(null);
		if (!(value instanceof Collection)) {
			return Collections.emptyList();
		}
		return (Collection<Object>) value;
	}

	private static Object findHandlerMappingWithControllers(Object applicationContext) throws Exception {
		ClassLoader classLoader = resolveClassLoader(applicationContext.getClass().getClassLoader());
		Class<?> handlerMappingClass = Class.forName(HANDLER_MAPPING_CLASS, false, classLoader);
		Method getBeansOfType = applicationContext.getClass().getMethod("getBeansOfType", Class.class);
		Object beans = getBeansOfType.invoke(applicationContext, handlerMappingClass);
		if (!(beans instanceof Map)) {
			return null;
		}
		Map<?, ?> beanMap = (Map<?, ?>) beans;
		for (Object handlerMapping : beanMap.values()) {
			Method getHandlerMethods = handlerMappingClass.getMethod("getHandlerMethods");
			Object handlerMethods = getHandlerMethods.invoke(handlerMapping);
			if (handlerMethods instanceof Map && !((Map<?, ?>) handlerMethods).isEmpty()) {
				return handlerMapping;
			}
		}
		return null;
	}

	private static void registerRoute(Object applicationContext, Object handlerMapping, SpringProxyConfig config)
			throws Exception {
		ClassLoader classLoader = resolveClassLoader(applicationContext.getClass().getClassLoader());
		Class<?> requestHandlerClass = Class.forName(HTTP_REQUEST_HANDLER_CLASS, false, classLoader);
		Method handleRequestMethod = requestHandlerClass.getMethod("handleRequest",
				requestHandlerClass.getMethods()[0].getParameterTypes());
		Object handler = Proxy.newProxyInstance(classLoader, new Class<?>[] { requestHandlerClass },
				new HttpProxyInvocationHandler(config));
		Object mappingInfo = buildRequestMappingInfo(handlerMapping, classLoader, config.getRoutePattern());

		String registrationKey = Integer.toHexString(System.identityHashCode(applicationContext)) + "@"
				+ config.getRoutePattern();
		RouteRegistration previous = ROUTES.remove(registrationKey);
		if (previous != null) {
			previous.unregister();
		}

		Method registerMapping = handlerMapping.getClass().getMethod("registerMapping", mappingInfo.getClass(),
				Object.class, Method.class);
		registerMapping.invoke(handlerMapping, mappingInfo, handler, handleRequestMethod);
		ROUTES.put(registrationKey, new RouteRegistration(handlerMapping, mappingInfo));
	}

	private static Object buildRequestMappingInfo(Object handlerMapping, ClassLoader classLoader, String routePattern)
			throws Exception {
		Class<?> requestMappingInfoClass = Class.forName(REQUEST_MAPPING_INFO_CLASS, false, classLoader);
		Method pathsMethod = requestMappingInfoClass.getMethod("paths", String[].class);
		Object builder = pathsMethod.invoke(null, new Object[] { new String[] { routePattern } });
		try {
			Class<?> builderConfigurationClass = Class.forName(BUILDER_CONFIGURATION_CLASS, false, classLoader);
			Method getBuilderConfiguration = handlerMapping.getClass().getMethod("getBuilderConfiguration");
			Object builderConfiguration = getBuilderConfiguration.invoke(handlerMapping);
			Method optionsMethod = builder.getClass().getDeclaredMethod("options", builderConfigurationClass);
			optionsMethod.setAccessible(true);
			optionsMethod.invoke(builder, builderConfiguration);
		} catch (Exception ignore) {
			// compatible with older spring versions
		}
		Method buildMethod = builder.getClass().getDeclaredMethod("build");
		buildMethod.setAccessible(true);
		return buildMethod.invoke(builder);
	}

	private static ClassLoader resolveClassLoader(ClassLoader classLoader) {
		return classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
	}

	static final class SpringProxyConfig {
		private final String routePattern;
		private final String targetHost;
		private final int targetPort;
		private final boolean encrypt;
		private final String encryptKey;
		private final String encryptIv;

		SpringProxyConfig(String routePattern, String targetHost, int targetPort, boolean encrypt,
				String encryptKey, String encryptIv) {
			this.routePattern = normalizeRoute(routePattern);
			this.targetHost = targetHost == null || targetHost.trim().isEmpty() ? "127.0.0.1" : targetHost.trim();
			this.targetPort = targetPort <= 0 ? 8563 : targetPort;
			this.encrypt = encrypt;
			this.encryptKey = encryptKey == null || encryptKey.isEmpty() ? "arthas" : encryptKey;
			this.encryptIv = encryptIv == null || encryptIv.isEmpty() ? "0123456789abcdef" : encryptIv;
		}

		String getRoutePattern() {
			return routePattern;
		}

		String getTargetHost() {
			return targetHost;
		}

		int getTargetPort() {
			return targetPort;
		}

		boolean isEncrypt() {
			return encrypt;
		}

		String getEncryptKey() {
			return encryptKey;
		}

		String getEncryptIv() {
			return encryptIv;
		}

		String getRoutePrefix() {
			return routePattern.endsWith("/**") ? routePattern.substring(0, routePattern.length() - 3) : routePattern;
		}

		private static String normalizeRoute(String routePattern) {
			String normalized = routePattern == null || routePattern.trim().isEmpty() ? "/arthas/**"
					: routePattern.trim();
			if (!normalized.startsWith("/")) {
				normalized = "/" + normalized;
			}
			if (!normalized.endsWith("/**")) {
				normalized = normalized.endsWith("/") ? normalized + "**" : normalized + "/**";
			}
			return normalized;
		}
	}

	private static final class RouteRegistration {
		private final Object handlerMapping;
		private final Object mappingInfo;

		private RouteRegistration(Object handlerMapping, Object mappingInfo) {
			this.handlerMapping = handlerMapping;
			this.mappingInfo = mappingInfo;
		}

		private void unregister() {
			try {
				Method unregisterMapping = handlerMapping.getClass().getMethod("unregisterMapping",
						mappingInfo.getClass());
				unregisterMapping.invoke(handlerMapping, mappingInfo);
			} catch (Exception ignore) {
				// ignore cleanup failures
			}
		}
	}

	private static final class HttpProxyInvocationHandler implements InvocationHandler {
		private final SpringProxyConfig config;

		private HttpProxyInvocationHandler(SpringProxyConfig config) {
			this.config = config;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return method.invoke(this, args);
			}
			Object request = args[0];
			Object response = args[1];
			handle(request, response);
			return null;
		}

		private void handle(Object request, Object response) throws IOException {
			HttpURLConnection connection = null;
			try {
				connection = (HttpURLConnection) new URL(buildTargetUrl(request)).openConnection();
				connection.setInstanceFollowRedirects(false);
				String requestMethod = invokeString(request, "getMethod");
				connection.setRequestMethod(requestMethod);
				copyRequestHeaders(request, connection);
				byte[] requestBody = readAllBytes((InputStream) request.getClass().getMethod("getInputStream")
						.invoke(request));
				if (config.isEncrypt() && requestBody.length > 0) {
					requestBody = decryptPayload(requestBody, config);
				}
				if (shouldWriteBody(requestMethod, requestBody)) {
					connection.setDoOutput(true);
					connection.setRequestProperty("Content-Length", Integer.toString(requestBody.length));
					try (OutputStream outputStream = connection.getOutputStream()) {
						outputStream.write(requestBody);
					}
				}
				setStatus(response, connection.getResponseCode());
				copyResponseHeaders(connection, response);
				byte[] responseBody = readConnectionBody(connection);
				if (config.isEncrypt() && responseBody.length > 0) {
					responseBody = encryptPayload(responseBody, config);
					setHeader(response, ENCRYPTION_HEADER, AES);
					setHeader(response, "Content-Type", "text/plain;charset=UTF-8");
				}
				writeResponseBody(response, responseBody);
			} catch (Exception e) {
				sendError(response, 502, "Arthas proxy failed: " + e.getMessage());
			} finally {
				if (connection != null) {
					connection.disconnect();
				}
			}
		}

		private String buildTargetUrl(Object request) throws Exception {
			String requestUri = invokeString(request, "getRequestURI");
			String contextPath = invokeNullableString(request, "getContextPath");
			String queryString = invokeNullableString(request, "getQueryString");
			String path = requestUri;
			if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
				path = path.substring(contextPath.length());
			}
			String routePrefix = config.getRoutePrefix();
			String forwardedPath = path.startsWith(routePrefix) ? path.substring(routePrefix.length()) : path;
			if (forwardedPath.isEmpty()) {
				forwardedPath = "/";
			}
			StringBuilder builder = new StringBuilder("http://")
					.append(config.getTargetHost())
					.append(':')
					.append(config.getTargetPort());
			if (!forwardedPath.startsWith("/")) {
				builder.append('/');
			}
			builder.append(forwardedPath);
			if (queryString != null && !queryString.isEmpty()) {
				builder.append('?').append(queryString);
			}
			return builder.toString();
		}

		private void copyRequestHeaders(Object request, HttpURLConnection connection) throws Exception {
			Object headerNames = request.getClass().getMethod("getHeaderNames").invoke(request);
			if (!(headerNames instanceof java.util.Enumeration)) {
				return;
			}
			java.util.Enumeration<?> enumeration = (java.util.Enumeration<?>) headerNames;
			while (enumeration.hasMoreElements()) {
				String headerName = String.valueOf(enumeration.nextElement());
				if (shouldSkipHeader(headerName) || ENCRYPTION_HEADER.equalsIgnoreCase(headerName)) {
					continue;
				}
				Object headers = request.getClass().getMethod("getHeaders", String.class).invoke(request, headerName);
				if (!(headers instanceof java.util.Enumeration)) {
					continue;
				}
				java.util.Enumeration<?> values = (java.util.Enumeration<?>) headers;
				while (values.hasMoreElements()) {
					connection.addRequestProperty(headerName, String.valueOf(values.nextElement()));
				}
			}
		}

		private void copyResponseHeaders(HttpURLConnection connection, Object response) throws Exception {
			for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
				if (entry.getKey() == null || shouldSkipHeader(entry.getKey())) {
					continue;
				}
				for (String value : entry.getValue()) {
					addHeader(response, entry.getKey(), value);
				}
			}
		}

		private byte[] readConnectionBody(HttpURLConnection connection) throws IOException {
			InputStream inputStream = connection.getResponseCode() >= 400 ? connection.getErrorStream()
					: connection.getInputStream();
			if (inputStream == null) {
				return new byte[0];
			}
			String contentEncoding = connection.getContentEncoding();
			if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip")) {
				return readAllBytes(new GZIPInputStream(inputStream));
			}
			return readAllBytes(inputStream);
		}

		private void setStatus(Object response, int statusCode) throws Exception {
			response.getClass().getMethod("setStatus", int.class).invoke(response, Integer.valueOf(statusCode));
		}

		private void setHeader(Object response, String name, String value) throws Exception {
			response.getClass().getMethod("setHeader", String.class, String.class).invoke(response, name, value);
		}

		private void addHeader(Object response, String name, String value) throws Exception {
			response.getClass().getMethod("addHeader", String.class, String.class).invoke(response, name, value);
		}

		private void writeResponseBody(Object response, byte[] body) throws Exception {
			if (body == null || body.length == 0) {
				return;
			}
			response.getClass().getMethod("setContentLength", int.class).invoke(response, Integer.valueOf(body.length));
			OutputStream outputStream = (OutputStream) response.getClass().getMethod("getOutputStream").invoke(response);
			outputStream.write(body);
			outputStream.flush();
		}

		private void sendError(Object response, int statusCode, String message) throws IOException {
			try {
				response.getClass().getMethod("sendError", int.class, String.class).invoke(response,
						Integer.valueOf(statusCode), message);
			} catch (Exception ex) {
				throw new IOException(message, ex);
			}
		}

		private byte[] encryptPayload(byte[] body, SpringProxyConfig config) throws GeneralSecurityException {
			Cipher cipher = Cipher.getInstance(AES);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(normalizeKey(config.getEncryptKey()), "AES"),
					new IvParameterSpec(normalizeKey(config.getEncryptIv())));
			return Base64.getEncoder().encode(cipher.doFinal(body));
		}

		private byte[] decryptPayload(byte[] body, SpringProxyConfig config) throws GeneralSecurityException {
			Cipher cipher = Cipher.getInstance(AES);
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(normalizeKey(config.getEncryptKey()), "AES"),
					new IvParameterSpec(normalizeKey(config.getEncryptIv())));
			return cipher.doFinal(Base64.getDecoder().decode(new String(body, StandardCharsets.UTF_8).trim()));
		}

		private boolean shouldWriteBody(String method, byte[] body) {
			return body != null && body.length > 0 && !"GET".equalsIgnoreCase(method)
					&& !"HEAD".equalsIgnoreCase(method);
		}

		private boolean shouldSkipHeader(String headerName) {
			return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase());
		}

		private byte[] normalizeKey(String value) {
			return Arrays.copyOf(value.getBytes(StandardCharsets.UTF_8), 16);
		}

		private String invokeString(Object target, String methodName) throws Exception {
			Object value = target.getClass().getMethod(methodName).invoke(target);
			return value == null ? null : String.valueOf(value);
		}

		private String invokeNullableString(Object target, String methodName) throws Exception {
			Object value = target.getClass().getMethod(methodName).invoke(target);
			return value == null ? null : String.valueOf(value);
		}

		private byte[] readAllBytes(InputStream inputStream) throws IOException {
			if (inputStream == null) {
				return new byte[0];
			}
			try (InputStream in = inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[4096];
				int read;
				while ((read = in.read(buffer)) != -1) {
					outputStream.write(buffer, 0, read);
				}
				return outputStream.toByteArray();
			}
		}
	}
}



