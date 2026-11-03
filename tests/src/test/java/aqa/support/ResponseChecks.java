package aqa.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ResponseChecks {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ResponseChecks() { }

    public static JsonNode jsonObject(HttpFixture.Response response) {
        JsonNode body = assertDoesNotThrow(() -> JSON.readTree(response.body()), "Ожидается JSON");
        assertNotNull(body, "Тело ответа не должно быть пустым");
        assertTrue(body.isObject(), "Тело ответа должно быть JSON-объектом");
        return body;
    }

    public static void assertNonBlankText(JsonNode body, String field) {
        JsonNode value = body.path(field);
        assertTrue(value.isTextual() && !value.asText().isBlank(),
                "Поле " + field + " должно содержать непустой текст");
    }
}
