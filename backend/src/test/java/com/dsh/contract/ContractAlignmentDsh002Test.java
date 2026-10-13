package com.dsh.contract;

import com.dsh.api.dto.BudgetView;
import com.dsh.api.dto.CapabilityStatus;
import com.dsh.api.dto.Message;
import com.dsh.api.dto.ModelOptions;
import com.dsh.api.dto.ModelProfile;
import com.dsh.api.dto.ModelValidation;
import com.dsh.api.dto.ModelValidationCheck;
import com.dsh.api.dto.Project;
import com.dsh.api.dto.Provider;
import com.dsh.api.dto.Run;
import com.dsh.api.dto.RunRole;
import com.dsh.api.dto.RunState;
import com.dsh.api.dto.Turn;
import com.dsh.api.dto.TurnAccepted;
import com.dsh.api.dto.Usage;
import com.dsh.api.dto.VerificationStatus;
import com.dsh.api.dto.Thread;
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
 * DSH-002 验收：Project/ModelProfile/Thread/Turn/Run/Message 等持久化 DTO 与契约对齐。
 * 将 Jackson 序列化结果交给 openapi.json 对应 Schema（2020-12）校验。
 */
class ContractAlignmentDsh002Test {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Instant T = Instant.parse("2026-09-22T08:00:00Z");

    private static JsonNode contractComponents;

    @BeforeAll
    static void loadContract() throws Exception {
        Path openapi = Paths.get("..", "doc", "项目文档", "openapi.json").normalize().toAbsolutePath();
        assertTrue(Files.exists(openapi), "找不到契约文件: " + openapi);
        contractComponents = MAPPER.readTree(Files.readString(openapi)).get("components");
    }

    private static Set<ValidationMessage> validateAgainst(String schemaName, JsonNode instance) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("allOf", MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("$ref", "#/components/schemas/" + schemaName)));
        root.set("components", contractComponents);
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        return schema.validate(instance);
    }

    private static void assertValid(String schemaName, JsonNode instance) {
        Set<ValidationMessage> errors = validateAgainst(schemaName, instance);
        assertTrue(errors.isEmpty(), schemaName + " 校验失败: " + errors);
    }

    private static void assertInvalid(String schemaName, JsonNode instance) {
        Set<ValidationMessage> errors = validateAgainst(schemaName, instance);
        assertFalse(errors.isEmpty(), schemaName + " 预期校验失败，实际通过");
    }

    private static JsonNode serialize(Object dto) throws Exception {
        return MAPPER.readTree(MAPPER.writeValueAsString(dto));
    }

    @Test
    void projectMatchesContract() throws Exception {
        Project project = new Project("proj_a1", "示例项目", "D:/code/demo", false, 1, T, T);
        assertValid("Project", serialize(project));

        ObjectNode withExtra = (ObjectNode) serialize(project);
        withExtra.put("trusted2", true);
        assertInvalid("Project", withExtra);
    }

    @Test
    void threadMatchesContract() throws Exception {
        Thread thread = new Thread("thr_a1", "proj_a1", "新会话", "prof_a1", null, 1, T, T);
        assertValid("Thread", serialize(thread));

        ObjectNode withExtra = (ObjectNode) serialize(thread);
        withExtra.put("active", true);
        assertInvalid("Thread", withExtra);
    }

    @Test
    void turnMatchesContract() throws Exception {
        Turn turn = new Turn("turn_a1", "thr_a1", "run_a1", "输入内容",
                RunState.QUEUED, VerificationStatus.PENDING, null, 1, T, T);
        assertValid("Turn", serialize(turn));

        ObjectNode badState = (ObjectNode) serialize(turn);
        badState.put("state", "RUNNING_FOREVER");
        assertInvalid("Turn", badState);
    }

    @Test
    void turnAcceptedMatchesContract() throws Exception {
        TurnAccepted accepted = new TurnAccepted("turn_a1", "run_a1", RunState.QUEUED, "0");
        assertValid("TurnAccepted", serialize(accepted));

        ObjectNode withExtra = (ObjectNode) serialize(accepted);
        withExtra.put("events", "[]");
        assertInvalid("TurnAccepted", withExtra);
    }

    @Test
    void messageMatchesContract() throws Exception {
        Message message = new Message("msg_a1", "thr_a1", "turn_a1", "user", "你好", "1", T);
        assertValid("Message", serialize(message));

        ObjectNode missingContent = (ObjectNode) serialize(message);
        missingContent.remove("content");
        assertInvalid("Message", missingContent);
    }

    @Test
    void modelProfileMatchesContract() throws Exception {
        ModelOptions options = new ModelOptions(ModelOptions.Thinking.disabled, null, 4096, false);
        ModelProfile profile = new ModelProfile("prof_a1", "本地fake", Provider.fake, "fake-model",
                "http://127.0.0.1:9", null, options, CapabilityStatus.UNKNOWN, 1, T, T);
        assertValid("ModelProfile", serialize(profile));

        ObjectNode badProvider = (ObjectNode) serialize(profile);
        badProvider.put("provider", "anthropic");
        assertInvalid("ModelProfile", badProvider);
    }

    @Test
    void modelValidationMatchesContract() throws Exception {
        ModelValidation validation = new ModelValidation("prof_a1", ModelValidation.Level.CONNECTIVITY, true, T,
                List.of(new ModelValidationCheck("CONNECTIVITY", true, "fake provider 本地可用")),
                Usage.unknown());
        assertValid("ModelValidation", serialize(validation));

        ObjectNode badLevel = (ObjectNode) serialize(validation);
        badLevel.put("level", "SOMETHING");
        assertInvalid("ModelValidation", badLevel);
    }

    @Test
    void runBudgetAndUsageMatchContract() throws Exception {
        BudgetView budget = new BudgetView(24, 0, 80, 0, 200000, null, 0, T, "UNKNOWN");
        Run run = new Run("run_a1", "turn_a1", null, "trace_a1", RunRole.MAIN, RunState.QUEUED,
                VerificationStatus.PENDING, null, 1, "ws_a1",
                com.dsh.common.Digest.sha256Hex("run_a1"),
                budget, Usage.unknown(), null, null, 1, T, T);
        assertValid("Run", serialize(run));
        assertValid("BudgetView", serialize(budget));
        assertValid("Usage", serialize(Usage.unknown()));

        ObjectNode missingBudgetField = (ObjectNode) serialize(budget);
        missingBudgetField.remove("maxModelRequests");
        assertInvalid("BudgetView", missingBudgetField);
    }
}
