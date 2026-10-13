package com.dsh.contract;

import com.dsh.api.dto.Usage;
import com.dsh.api.dto.event.ContentBlock;
import com.dsh.api.dto.event.EventEnvelope;
import com.dsh.api.dto.event.EventPage;
import com.dsh.api.dto.event.EventPayloads;
import com.dsh.api.dto.event.ToolOutcomeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH-003 验收：EventEnvelope（doc/events.schema.json 独立事件 Schema）与
 * EventPage（openapi.json components）正反例契约对齐。
 */
class ContractAlignmentDsh003Test {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Instant T = Instant.parse("2026-09-23T08:00:00Z");

    private static JsonSchema eventSchema;
    private static JsonNode openapiComponents;

    @BeforeAll
    static void loadContract() throws Exception {
        Path eventsDoc = Paths.get("..", "doc", "项目文档", "events.schema.json").normalize().toAbsolutePath();
        assertTrue(Files.exists(eventsDoc), "找不到契约文件: " + eventsDoc);
        JsonNode eventsSchemaNode = MAPPER.readTree(Files.readString(eventsDoc));

        // 文档顶层是 WsClientFrame/WsServerFrame/EventEnvelope 三选一；这里只验证 EventEnvelope
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        wrapper.put("$ref", "#/$defs/EventEnvelope");
        wrapper.set("$defs", eventsSchemaNode.get("$defs"));
        eventSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(wrapper);

        Path openapi = Paths.get("..", "doc", "项目文档", "openapi.json").normalize().toAbsolutePath();
        openapiComponents = MAPPER.readTree(Files.readString(openapi)).get("components");
    }

    private Set<ValidationMessage> eventErrors(JsonNode instance) {
        return eventSchema.validate(instance);
    }

    private void assertValidEvent(JsonNode instance) {
        Set<ValidationMessage> errors = eventErrors(instance);
        assertTrue(errors.isEmpty(), "事件信封应通过契约校验: " + errors);
    }

    private void assertInvalidEvent(JsonNode instance) {
        Set<ValidationMessage> errors = eventErrors(instance);
        assertFalse(errors.isEmpty(), "事件信封预期校验失败，实际通过: " + instance);
    }

    private Set<ValidationMessage> validateAgainstOpenapi(String schemaName, JsonNode instance) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("allOf", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("$ref", "#/components/schemas/" + schemaName)));
        root.set("components", openapiComponents);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root).validate(instance);
    }

    private JsonNode envelope(String type, Object payload) {
        EventEnvelope e = new EventEnvelope("evt_a1", "proj_a1", "thr_a1", "turn_a1", "run_a1",
                null, "trace_a1", "span_a1", "1", T, "1.0", type, MAPPER.valueToTree(payload));
        return MAPPER.valueToTree(e);
    }

    @Test
    void stateChangedEnvelopeMatchesContract() {
        assertValidEvent(envelope("run.state_changed",
                new EventPayloads.StateChanged(null, "QUEUED", 1)));
        assertValidEvent(envelope("run.state_changed",
                new EventPayloads.StateChanged("RUNNING", "COMPLETED", 4)));

        ObjectNode bad = (ObjectNode) envelope("run.state_changed",
                new EventPayloads.StateChanged("RUNNING", "COMPLETED", 4));
        bad.put("sequence", "0"); // sequence 不允许 0
        assertInvalidEvent(bad);

        ObjectNode badVersion = (ObjectNode) envelope("run.state_changed",
                new EventPayloads.StateChanged("RUNNING", "BOGUS", 4));
        assertInvalidEvent(badVersion);
    }

    @Test
    void runTerminalEnvelopesMatchContract() {
        assertValidEvent(envelope("run.completed", new EventPayloads.RunCompleted(
                "COMPLETED", "NOT_REQUIRED", "NO_CHANGE", "回答完成", 4)));
        assertValidEvent(envelope("run.completed", new EventPayloads.RunCompleted(
                "COMPLETED", "PASSED", "VERIFICATION_PASSED", "验证通过", 4)));
        // run.completed 不允许 PENDING
        assertInvalidEvent(envelope("run.completed", new EventPayloads.RunCompleted(
                "COMPLETED", "PENDING", "NO_CHANGE", "未验证", 4)));

        assertValidEvent(envelope("run.failed", new EventPayloads.RunFailed(
                "FAILED", "PROVIDER_ERROR", "供应商错误", 4)));
        assertValidEvent(envelope("run.cancelled", new EventPayloads.RunCancelled(
                "CANCELLED", "USER_CANCELLED", 4)));
        assertValidEvent(envelope("run.input_applied",
                new EventPayloads.InputApplied("msg_a1", 1)));

        // 未知 type 不能匹配 oneOf
        assertInvalidEvent(envelope("run.whatever",
                new EventPayloads.RunFailed("FAILED", "PROVIDER_ERROR", "x", 4)));
    }

    @Test
    void assistantAndModelEnvelopesMatchContract() {
        assertValidEvent(envelope("assistant.message.completed",
                new EventPayloads.MessageCompleted("msg_a1", "回答正文")));
        assertValidEvent(envelope("assistant.text.delta",
                new EventPayloads.TextDelta("msg_a1", 0, "片段")));

        Usage usage = new Usage("KNOWN", 13, 7, null, null, null, null, null);
        assertValidEvent(envelope("model.completed", new EventPayloads.ModelCompleted(
                "fake-answer", "mresp_a1", "stop", 12, usage)));
        assertValidEvent(envelope("model.completed", new EventPayloads.ModelCompleted(
                "fake-answer", null, "tool_calls", 0, Usage.unknown())));

        ObjectNode missingField = (ObjectNode) envelope("model.completed", new EventPayloads.ModelCompleted(
                "fake-answer", null, "stop", 0, Usage.unknown()));
        ((ObjectNode) missingField.get("payload")).remove("finishReason");
        assertInvalidEvent(missingField);
    }

    @Test
    void toolEnvelopesAndOutcomeMatchContract() {
        ToolOutcomeDto outcome = new ToolOutcomeDto(
                "SUCCEEDED", null, "echo: hi",
                List.of(new ContentBlock.TextBlock("echo: hi")),
                null, List.of(), false, 1, null, false, "call_a1", "echo");
        assertValidEvent(envelope("tool.started",
                new EventPayloads.ToolStarted("call_a1", "echo", "echo args")));
        assertValidEvent(envelope("tool.completed",
                new EventPayloads.ToolCompleted("call_a1", "echo", outcome)));

        ToolOutcomeDto failed = new ToolOutcomeDto(
                "FAILED", "UNKNOWN_TOOL", "未挂载工具", List.of(),
                null, List.of(), false, 0, null, true, "call_a2", "nope");
        assertValidEvent(envelope("tool.completed",
                new EventPayloads.ToolCompleted("call_a2", "nope", failed)));

        // 非法 status 必须被拒
        ToolOutcomeDto badStatus = new ToolOutcomeDto(
                "MAYBE", null, "x", List.of(), null, List.of(), false, 0, null, true, "call_a3", "echo");
        assertInvalidEvent(envelope("tool.completed",
                new EventPayloads.ToolCompleted("call_a3", "echo", badStatus)));

        // ToolOutcome 12 个字段全部 required
        ObjectNode toolPayload = (ObjectNode) MAPPER.valueToTree(
                new EventPayloads.ToolCompleted("call_a1", "echo", outcome));
        ((ObjectNode) toolPayload.get("result")).remove("durationMs");
        ObjectNode missingResultField = (ObjectNode) envelope("tool.completed",
                new EventPayloads.ToolCompleted("call_a1", "echo", outcome));
        missingResultField.set("payload", toolPayload);
        assertInvalidEvent(missingResultField);
    }

    @Test
    void envelopeCommonFieldsEnforced() {
        // schemaVersion 必须是 "1.0"
        ObjectNode badVersion = (ObjectNode) envelope("run.input_applied",
                new EventPayloads.InputApplied("msg_a1", 1));
        badVersion.put("schemaVersion", "2.0");
        assertInvalidEvent(badVersion);

        // 信封不允许额外字段
        ObjectNode extra = (ObjectNode) envelope("run.input_applied",
                new EventPayloads.InputApplied("msg_a1", 1));
        extra.put("trace", "nope");
        assertInvalidEvent(extra);

        // eventId 模式受限
        ObjectNode badId = (ObjectNode) envelope("run.input_applied",
                new EventPayloads.InputApplied("msg_a1", 1));
        badId.put("eventId", "bad id!");
        assertInvalidEvent(badId);
    }

    @Test
    void eventPageMatchesOpenapi() throws Exception {
        EventEnvelope e1 = new EventEnvelope("evt_a1", "proj_a1", "thr_a1", "turn_a1", "run_a1",
                null, "trace_a1", "span_a1", "1", T, "1.0", "run.state_changed",
                MAPPER.valueToTree(new EventPayloads.StateChanged(null, "QUEUED", 1)));
        EventEnvelope e2 = new EventEnvelope("evt_a2", "proj_a1", "thr_a1", "turn_a1", "run_a1",
                null, "trace_a1", "span_a1", "2", T, "1.0", "run.completed",
                MAPPER.valueToTree(new EventPayloads.RunCompleted("COMPLETED", "NOT_REQUIRED", "NO_CHANGE", "ok", 4)));
        EventPage page = new EventPage(List.of(e1, e2), "2", false, "2");
        JsonNode instance = MAPPER.readTree(MAPPER.writeValueAsString(page));
        assertTrue(validateAgainstOpenapi("EventPage", instance).isEmpty(),
                "EventPage 应通过 openapi 契约校验: " + validateAgainstOpenapi("EventPage", instance));

        ObjectNode extra = (ObjectNode) MAPPER.valueToTree(page);
        extra.put("cursor", "9");
        assertFalse(validateAgainstOpenapi("EventPage", extra).isEmpty());
    }
}
