package com.dsh.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VerificationPlanVersionTest {
    @TempDir Path root;
    VerificationPlan load(String value) throws Exception {
        Files.createDirectories(root.resolve(".dsh"));
        // YAML escapes can represent invalid surrogate values even though raw UTF-8 cannot.
        var json=new ObjectMapper();
        json.getFactory().configure(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(),true);
        Files.writeString(root.resolve(".dsh/verify.yaml"),"command: echo ok\nplanVersion: "+json.writeValueAsString(value)+"\n");
        return VerificationPlan.load(root);
    }
    @ParameterizedTest @ValueSource(strings={"a","中","😀"})
    void acceptsExactly64CodePointsAndRejects65(String point) throws Exception {
        String boundary=point.repeat(64);assertEquals(boundary,load(boundary).planVersion());
        assertThrows(IllegalArgumentException.class,()->load(point.repeat(65)));
    }
    @Test void preservesSpacesAndTheirIdentityAndRejectsDatabaseSilentTruncationCase() throws Exception {
        String value=" "+"a".repeat(62)+" ";var plan=load(value);
        assertEquals(value,plan.planVersion());assertNotEquals(plan.displayDigest(),load(value.strip()).displayDigest());
        assertThrows(IllegalArgumentException.class,()->load("a".repeat(64)+" "));
    }
    @ParameterizedTest @ValueSource(strings={"v\u0000x","\uD800","\uDC00","v\uD800x","v\uDC00x",""," ","\u2003"})
    void rejectsUnstorableUnicodeAndBlankValuesWithoutEchoingValue(String value) throws Exception {
        var error=assertThrows(IllegalArgumentException.class,()->load(value));
        assertTrue(error.getMessage().startsWith("无效的 .dsh/verify.yaml"));
    }
    @Test void doesNotNormalizeUnicodeOrTreatNonbreakingSpaceAsJavaWhitespace() throws Exception {
        assertEquals("\u00a0",load("\u00a0").planVersion());
        assertNotEquals(load("é").displayDigest(),load("e\u0301").displayDigest());
        assertEquals("e\u0301".repeat(32),load("e\u0301".repeat(32)).planVersion());
        assertThrows(IllegalArgumentException.class,()->load("e\u0301".repeat(33)));
    }
}
