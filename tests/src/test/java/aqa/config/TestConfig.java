package aqa.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

public record TestConfig(URI appUrl, URI mockUrl, String apiKey) {
    public TestConfig {
        requireLoopback(appUrl, 8080);
        requireLoopback(mockUrl, 8888);
        if (!"qazWSXedc".equals(apiKey)) {
            throw new IllegalArgumentException("Разрешён только тестовый API-ключ из задания");
        }
    }

    public static TestConfig load() {
        Properties values = new Properties();
        try (InputStream input = TestConfig.class.getResourceAsStream("/test.properties")) {
            Objects.requireNonNull(input, "Не найден ресурс test.properties");
            values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return new TestConfig(URI.create(values.getProperty("app.url")),
                    URI.create(values.getProperty("mock.url")), values.getProperty("api.key"));
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось прочитать тестовую конфигурацию", error);
        }
    }

    private static void requireLoopback(URI value, int port) {
        if (!URI.create("http://127.0.0.1:" + port).equals(value)) {
            throw new IllegalArgumentException("Разрешён только HTTP loopback на порту " + port);
        }
    }
}
