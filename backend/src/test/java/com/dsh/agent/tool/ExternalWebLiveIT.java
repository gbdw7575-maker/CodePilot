package com.dsh.agent.tool;

import com.dsh.application.DshProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicitly invoked live probe; excluded from the normal *Test suite. */
class ExternalWebLiveIT {

    private static final String URL =
            "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html";

    @Test
    void fetchesPublicOracleDocumentationWithSourceMetadata() {
        DshProperties properties = new DshProperties(null, null, null, null, null, null, null,
                null, null, null, null,
                new DshProperties.Web(true, false, 10, 20, 65536, 20000, ""), null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        WebFetchTool tool = new WebFetchTool(properties);

        ToolOutcome outcome = tool.execute(mapper.createObjectNode().put("url", URL),
                new ToolExecutionContext("live-web", "live-thread", "live-turn", "call-live",
                        "live-project",
                        null, Instant.now().plusSeconds(60), null, null, mapper));

        assertEquals("SUCCEEDED", outcome.status(), outcome.summary());
        assertEquals(URL, outcome.structuredContent().path("url").asText());
        assertEquals(200, outcome.structuredContent().path("status").asInt());
        assertTrue(outcome.structuredContent().path("finalUrl").asText().startsWith("https://docs.oracle.com/"));
        assertFalse(outcome.structuredContent().path("fetchedAt").asText().isBlank());
        assertTrue(outcome.text().contains("Class String"), "public API heading should be present");
    }
}
