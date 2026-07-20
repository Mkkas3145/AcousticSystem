package org.macaroon.acousticsystem.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AcousticLocalizationTest {
    @Test
    void koreanJapaneseAndEnglishContainTheSameOptions() {
        JsonObject english = language("en_us");
        JsonObject korean = language("ko_kr");
        JsonObject japanese = language("ja_jp");
        Set<String> expected = english.keySet();

        assertEquals(expected, korean.keySet());
        assertEquals(expected, japanese.keySet());
    }

    private static JsonObject language(String code) {
        String path = "/assets/acousticsystem/lang/" + code + ".json";
        var stream = AcousticLocalizationTest.class.getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError("Could not read " + path, exception);
        }
    }
}
