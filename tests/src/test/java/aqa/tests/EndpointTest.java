package aqa.tests;

import aqa.support.HttpFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static aqa.support.ResponseChecks.assertNonBlankText;
import static aqa.support.ResponseChecks.jsonObject;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("POST /endpoint: доступ, сессия и внешний сервис")
@Epic("Тестовое задание AQA")
@Feature("POST /endpoint")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
class EndpointTest {
    private final HttpFixture api = new HttpFixture();
    private final ObjectMapper json = new ObjectMapper();
    private String token;

    @BeforeAll
    @Timeout(100)
    void waitForServices() throws Exception {
        api.awaitServices();
    }

    @BeforeEach
    void prepareInfrastructure() throws Exception {
        api.resetMock();
    }

    @Test
    @DisplayName("POST /endpoint: LOGIN → ACTION → LOGOUT; после выхода действие запрещено")
    @Story("Жизненный цикл сессии")
    @Description("Пользователь входит, выполняет действие и выходит. После выхода действие отклоняется без обращения к внешнему сервису.")
    void sessionLifecycle() throws Exception {
        Allure.step("Дано: моки POST /auth и POST /doAction настроены на HTTP 200", () -> {
            token = newHexToken();
            api.stub("/auth", token, 200, "not a JSON response");
            api.stub("/doAction", token, 200, "another arbitrary body");
        });
        Allure.step("POST /endpoint, action=LOGIN, корректный token и API-ключ", () -> {
            var login = api.post(token, "LOGIN");
            JsonNode body = jsonObject(login);
            assertEquals("OK", body.path("result").asText());
            assertEquals(1, api.requestCount("/auth", token));
            assertEquals(1, api.totalRequestCount());
        });
        Allure.step("POST /endpoint, action=ACTION, авторизованный token", () -> {
            var action = api.post(token, "ACTION");
            JsonNode body = jsonObject(action);
            assertEquals("OK", body.path("result").asText());
            assertEquals(1, api.requestCount("/doAction", token));
            assertEquals(2, api.totalRequestCount());
        });
        Allure.step("POST /endpoint, action=LOGOUT", () -> {
            var logout = api.post(token, "LOGOUT");
            JsonNode body = jsonObject(logout);
            assertEquals("OK", body.path("result").asText());
            assertEquals(2, api.totalRequestCount());
        });
        Allure.step("повторяем POST /endpoint, action=ACTION после выхода", () -> {
            var afterLogout = api.post(token, "ACTION");
            JsonNode body = jsonObject(afterLogout);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(1, api.requestCount("/doAction", token));
            assertEquals(2, api.totalRequestCount());
        });
    }

    @ParameterizedTest(name = "POST /endpoint: {0}")
    @MethodSource("invalidKeys")
    @DisplayName("POST /endpoint: неверный или отсутствующий API-ключ")
    @Story("Контроль доступа")
    @Description("Без корректного API-ключа LOGIN отклоняется до обращения к внешнему сервису.")
    void rejectsInvalidApiKey(String description, String key) throws Exception {
        Allure.step("Дано: мок POST /auth настроен на HTTP 200", () -> {
            token = newHexToken();
            api.stub("/auth", token, 200, "any body");
        });
        Allure.step("POST /endpoint, action=LOGIN — " + description, () -> {
            var response = api.post(token, "LOGIN", key);
            JsonNode body = jsonObject(response);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(0, api.totalRequestCount());
        });
    }

    static Stream<Arguments> invalidKeys() {
        return Stream.of(
                Arguments.of("API-ключ отсутствует", null),
                Arguments.of("API-ключ неверный", "incorrect-test-key"));
    }

    @Test
    @DisplayName("POST /endpoint: LOGIN принимает token с буквой G")
    @Story("Допустимые символы token")
    @Description("По ТЗ буква G допустима: LOGIN должен вернуть OK и один раз вызвать /auth с переданным token.")
    void loginAcceptsTokenContainingG() throws Exception {
        Allure.step("Дано: токен из 31 буквы A и буквы G, валидный API-ключ; мок POST /auth настроен на HTTP 200", () -> {
            token = "A".repeat(31) + "G";
            api.stub("/auth", token, 200, "any body");
        });
        Allure.step("POST /endpoint, action=LOGIN, token с буквой G", () -> {
            var response = api.post(token, "LOGIN");
            JsonNode body = jsonObject(response);
            int authCalls = api.requestCount("/auth", token);
            Allure.addAttachment("Фактический результат", "application/json",
                    json.createObjectNode().put("httpStatus", response.status()).set("body", body).toString(), ".json");
            Allure.addAttachment("Вызовы POST /auth с переданным token", "text/plain", Integer.toString(authCalls), ".txt");
            assertAll("Токен G допустим по ТЗ",
                    () -> assertEquals(200, response.status(), "Успешный LOGIN должен вернуть HTTP 200"),
                    () -> assertEquals(json.createObjectNode().put("result", "OK"), body, "LOGIN должен вернуть result=OK"),
                    () -> assertEquals(1, authCalls, "Ожидается один POST /auth с этим token"));
        });
    }

    @Test
    @DisplayName("POST /endpoint: отказ авторизации не раскрывает API-ключи в логах")
    @Story("Конфиденциальность логов")
    @Description("При уникальном неверном ключе ожидаются HTTP 401 и отсутствие обоих API-ключей в новых логах приложения.")
    void authorizationFailureDoesNotLeakApiKeys() throws Exception {
        String rejectedKey = "DENIED-" + UUID.randomUUID();
        Allure.step("Дано: уникальный неверный тестовый API-ключ; с хоста зафиксирована граница новых логов", () -> {
            token = "A".repeat(32);
            api.stub("/auth", token, 200, "any body");
            api.beginLogCapture(rejectedKey);
        });
        var response = Allure.step("Когда: POST /endpoint, action=LOGIN, неверный API-ключ", () -> {
            try {
                return api.post(token, "LOGIN", rejectedKey);
            } finally {
                api.logRequestFinished();
            }
        });
        var facts = Allure.step("Когда: новые логи приложения собраны с хоста через docker compose logs",
                () -> api.logCaptureFacts());
        Allure.step("Тогда: HTTP 401; переданный и настроенный API-ключи отсутствуют в новых логах", () -> {
            Allure.addAttachment("Результат проверки логов", "application/json",
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(facts), ".json");
            assertAll("Отказ авторизации не должен раскрывать ключи",
                    () -> assertEquals(401, response.status(), "Ожидается HTTP 401"),
                    () -> assertFalse(facts.path("suppliedKeyLeaked").asBoolean(), "Переданный неверный ключ попал в новые логи приложения"),
                    () -> assertFalse(facts.path("configuredKeyLeaked").asBoolean(), "Настроенный валидный ключ попал в новые логи приложения"));
        });
    }

    @Test
    @DisplayName("POST /endpoint: ACTION без LOGIN запрещён")
    @Story("Контроль доступа")
    @Description("Пользователь без активной сессии не может выполнить действие.")
    void actionRequiresLogin() throws Exception {
        Allure.step("Дано: токен не авторизован; мок POST /doAction настроен на HTTP 200", () -> {
            token = newHexToken();
            api.stub("/doAction", token, 200, "any body");
        });
        Allure.step("POST /endpoint, action=ACTION, новый token", () -> {
            var response = api.post(token, "ACTION");
            JsonNode body = jsonObject(response);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(0, api.totalRequestCount());
        });
    }

    @Test
    @DisplayName("POST /endpoint: ошибка /auth не создаёт сессию")
    @Story("Ошибки внешнего сервиса")
    @Description("При отказе внешней авторизации вход и последующее действие должны быть отклонены.")
    void failedLoginDoesNotSaveToken() throws Exception {
        Allure.step("Дано: мок POST /auth настроен на HTTP 503, мок POST /doAction — на HTTP 200", () -> {
            token = newHexToken();
            api.stub("/auth", token, 503, "authorization unavailable");
            api.stub("/doAction", token, 200, "any body");
        });
        Allure.step("POST /endpoint, action=LOGIN при отказе внешней авторизации", () -> {
            var login = api.post(token, "LOGIN");
            JsonNode body = jsonObject(login);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(1, api.requestCount("/auth", token));
        });
        Allure.step("POST /endpoint, action=ACTION с тем же token", () -> {
            var action = api.post(token, "ACTION");
            JsonNode body = jsonObject(action);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(0, api.requestCount("/doAction", token));
            assertEquals(1, api.totalRequestCount());
        });
    }

    @Test
    @DisplayName("POST /endpoint: ошибка /doAction возвращается как ERROR")
    @Story("Ошибки внешнего сервиса")
    @Description("После успешного входа внешний отказ выполнения действия должен вернуться пользователю как ошибка.")
    void reportsActionFailure() throws Exception {
        Allure.step("Дано: мок POST /auth настроен на HTTP 200, мок POST /doAction — на HTTP 500", () -> {
            token = newHexToken();
            api.stub("/auth", token, 200, "any body");
            api.stub("/doAction", token, 500, "action unavailable");
        });
        Allure.step("POST /endpoint, action=LOGIN", () -> {
            var login = api.post(token, "LOGIN");
            JsonNode body = jsonObject(login);
            assertEquals("OK", body.path("result").asText());
            assertEquals(1, api.requestCount("/auth", token));
        });
        Allure.step("POST /endpoint, action=ACTION при внешней ошибке 500", () -> {
            var action = api.post(token, "ACTION");
            JsonNode body = jsonObject(action);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(1, api.requestCount("/doAction", token));
            assertEquals(2, api.totalRequestCount());
        });
    }

    @ParameterizedTest(name = "POST /endpoint: {0}")
    @MethodSource("invalidTokens")
    @DisplayName("POST /endpoint: валидация token")
    @Story("Проверка входных данных")
    @Description("Неверный token отклоняется до обращения к внешнему сервису.")
    void rejectsInvalidToken(String description, String invalidToken) throws Exception {
        Allure.step("Дано: " + description, () -> {
            if (invalidToken != null) {
                api.stub("/auth", invalidToken, 200, "any body");
            }
        });
        Allure.step("POST /endpoint, action=LOGIN, проверяемый token", () -> {
            var response = api.post(invalidToken, "LOGIN");
            JsonNode body = jsonObject(response);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(0, api.totalRequestCount());
        });
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(
                Arguments.of("token отсутствует", null),
                Arguments.of("token пустой", ""),
                Arguments.of("token короче 32 символов", "A".repeat(31)),
                Arguments.of("token длиннее 32 символов", "A".repeat(33)),
                Arguments.of("token содержит строчные буквы", "a".repeat(32)),
                Arguments.of("token содержит спецсимвол", "A".repeat(31) + "!"),
                Arguments.of("token содержит кириллицу", "A".repeat(31) + "Я"));
    }

    @ParameterizedTest(name = "POST /endpoint: {0}")
    @MethodSource("invalidActions")
    @DisplayName("POST /endpoint: валидация action")
    @Story("Проверка входных данных")
    @Description("Сервис принимает только LOGIN, ACTION и LOGOUT в указанном регистре.")
    void rejectsInvalidAction(String description, String action) throws Exception {
        Allure.step("Дано: корректный token и API-ключ; " + description, () -> {
            token = newHexToken();
            Allure.parameter("Проверяемое действие", action == null ? "<отсутствует>" : action);
        });
        Allure.step("POST /endpoint с проверяемым action", () -> {
            var response = api.post(token, action);
            JsonNode body = jsonObject(response);
            assertEquals("ERROR", body.path("result").asText());
            assertNonBlankText(body, "message");
            assertEquals(0, api.totalRequestCount());
        });
    }

    static Stream<Arguments> invalidActions() {
        return Stream.of(
                Arguments.of("action отсутствует", null),
                Arguments.of("action пустой", ""),
                Arguments.of("action неизвестен", "UNKNOWN"),
                Arguments.of("action в неверном регистре", "login"));
    }
    private static String newHexToken() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
}
