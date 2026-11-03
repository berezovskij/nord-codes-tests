package aqa.support;

import aqa.config.TestConfig;
import io.qameta.allure.Allure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HttpFixture {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final TestConfig config = TestConfig.load();
    private final URI app = config.appUrl();
    private final URI mock = config.mockUrl();
    private final String apiKey = config.apiKey();

    public void awaitServices() throws IOException, InterruptedException {
        await(mock.resolve("/__admin/mappings"), true);
        await(app.resolve("/endpoint"), false);
    }

    public void resetMock() throws IOException, InterruptedException {
        admin("POST", "/__admin/reset", null);
    }

    public void stub(String path, String token, int status, String body) throws IOException, InterruptedException {
        ObjectNode mapping = json.createObjectNode();
        ObjectNode request = requestPattern(path, token);
        ObjectNode headers = request.putObject("headers");
        headers.putObject("Content-Type").put("contains", "application/x-www-form-urlencoded");
        headers.putObject("Accept").put("contains", "application/json");
        mapping.set("request", request);
        mapping.putObject("response").put("status", status).put("body", body);
        Allure.addAttachment("WireMock: POST " + path + " → " + status,
                "application/json", json.writerWithDefaultPrettyPrinter().writeValueAsString(mapping), ".json");
        admin("POST", "/__admin/mappings", mapping);
    }

    public Response post(String token, String action) throws IOException, InterruptedException {
        return post(token, action, apiKey);
    }

    public Response post(String token, String action, String key) throws IOException, InterruptedException {
        List<String> fields = new ArrayList<>();
        if (token != null) {
            fields.add("token=" + encode(token));
        }
        if (action != null) {
            fields.add("action=" + encode(action));
        }
        HttpRequest.Builder request = request(app.resolve("/endpoint"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (key != null) {
            request.header("X-Api-Key", key);
        }
        String payload = String.join("&", fields);
        HttpRequest prepared = request.POST(HttpRequest.BodyPublishers.ofString(
                payload, StandardCharsets.UTF_8)).build();
        StringBuilder sent = new StringBuilder("POST " + prepared.uri() + " HTTP/1.1\n");
        prepared.headers().map().forEach((name, values) ->
                sent.append(name).append(": ").append(String.join(", ", values)).append('\n'));
        sent.append('\n').append(payload);
        Allure.addAttachment("Запрос POST /endpoint", "text/plain", sent.toString(), ".http");
        Response response = send(prepared);
        Allure.addAttachment("Ответ POST /endpoint: HTTP " + response.status(), "text/plain",
                "HTTP " + response.status() + "\n\n" + response.body(), ".http");
        return response;
    }

    public void beginLogCapture(String suppliedKey) throws IOException, InterruptedException {
        Path directory = Path.of("/work/log-check");
        Files.createDirectories(directory);
        for (String name : List.of("request.txt", "ready", "done", "facts.json")) {
            Files.deleteIfExists(directory.resolve(name));
        }
        Files.writeString(directory.resolve("request.tmp"), suppliedKey + "\n" + apiKey + "\n");
        Files.move(directory.resolve("request.tmp"), directory.resolve("request.txt"), StandardCopyOption.ATOMIC_MOVE);
        awaitFile(directory.resolve("ready"));
    }

    public void logRequestFinished() throws IOException {
        Files.writeString(Path.of("/work/log-check/done"), "done");
    }

    public JsonNode logCaptureFacts() throws IOException, InterruptedException {
        Path result = Path.of("/work/log-check/facts.json");
        awaitFile(result);
        JsonNode facts = json.readTree(Files.readString(result));
        List<String> keys = Files.readAllLines(Path.of("/work/log-check/request.txt"));
        Files.deleteIfExists(Path.of("/work/log-check/request.txt"));
        if (!facts.path("observed").asBoolean() || !facts.path("suppliedKeyLeaked").isBoolean()
                || !facts.path("configuredKeyLeaked").isBoolean() || keys.size() != 2) {
            throw new IOException("Сбор новых логов приложения с хоста не подтверждён");
        }
        return ((ObjectNode) facts).put("suppliedKey", keys.get(0)).put("configuredKey", keys.get(1));
    }

    private void awaitFile(Path path) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.isRegularFile(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("Истёк таймаут синхронизации сбора логов с хоста");
            }
            Thread.sleep(100);
        }
    }

    public int requestCount(String path, String token) throws IOException, InterruptedException {
        return count(requestPattern(path, token));
    }

    public int totalRequestCount() throws IOException, InterruptedException {
        return count(json.createObjectNode());
    }

    private int count(ObjectNode pattern) throws IOException, InterruptedException {
        Response response = admin("POST", "/__admin/requests/count", pattern);
        JsonNode count = json.readTree(response.body()).path("count");
        if (!count.isIntegralNumber()) {
            throw new IOException("Некорректный ответ WireMock admin API: отсутствует целочисленный count");
        }
        return count.intValue();
    }

    private ObjectNode requestPattern(String path, String token) {
        ObjectNode pattern = json.createObjectNode();
        pattern.put("method", "POST");
        pattern.put("urlPath", path);
        pattern.putArray("bodyPatterns").addObject().put("equalTo", "token=" + encode(token));
        return pattern;
    }

    private Response admin(String method, String path, JsonNode body) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8);
        Response response = send(request(mock.resolve(path))
                .header("Content-Type", "application/json")
                .method(method, publisher).build());
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("WireMock admin API: " + method + " " + path + ", HTTP " + response.status());
        }
        return response;
    }

    private void await(URI url, boolean requireSuccess) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                int status = send(request(url).GET().build()).status();
                if (status >= 200 && status < (requireSuccess ? 300 : 500)) {
                    return;
                }
                lastFailure = new IOException("HTTP " + status);
            } catch (IOException notReady) {
                lastFailure = notReady;
            }
            Thread.sleep(250);
        }
        throw new IOException("Сервис не готов за 45 секунд: " + url, lastFailure);
    }

    private HttpRequest.Builder request(URI url) {
        return HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3));
    }

    private Response send(HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(request, ignored -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = exchange.get(3, TimeUnit.SECONDS);
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (TimeoutException timeout) {
            exchange.cancel(true);
            throw new IOException("HTTP-запрос не завершился за 3 секунды: " + request.uri(), timeout);
        } catch (ExecutionException failure) {
            throw new IOException("HTTP-запрос завершился ошибкой: " + request.uri(), failure.getCause());
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            throw interrupted;
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean done;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                size += buffer.remaining();
            }
            if (size > MAX_BODY_BYTES) {
                subscription.cancel();
                onError(new IOException("Ответ превышает лимит " + MAX_BODY_BYTES + " байт"));
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            if (!done) {
                done = true;
                delegate.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Response(int status, String body) { }
}
