package dev.jacobwasbeast;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ManifestTest {
    @Test
    void manifestUsesAValidRangeForTheTestedServerVersion() throws Exception {
        try (var input = getClass().getResourceAsStream("/manifest.json")) {
            assertNotNull(input);
            var manifest = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var range = SemverRange.fromString(manifest.get("ServerVersion").getAsString());
            assertTrue(range.satisfies(Semver.fromString("0.7.3")));
            assertFalse(range.satisfies(Semver.fromString("0.6.9")));
            assertFalse(range.satisfies(Semver.fromString("0.8.0")));
        }
    }
}
