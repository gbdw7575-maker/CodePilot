package com.dsh.application;

import com.dsh.agent.control.CancellationToken;
import com.dsh.agent.model.GatewayRouter;
import com.dsh.agent.model.ModelGatewayException;
import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelRequest;
import com.dsh.agent.model.ModelResponse;
import com.dsh.agent.model.ModelToolCall;
import com.dsh.agent.tool.ToolDefinition;
import com.dsh.api.ApiException;
import com.dsh.api.dto.CapabilityStatus;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.ModelOptions;
import com.dsh.api.dto.ModelProfile;
import com.dsh.api.dto.ModelProfilePatch;
import com.dsh.api.dto.ModelProfileWrite;
import com.dsh.api.dto.ModelValidation;
import com.dsh.api.dto.ModelValidationCheck;
import com.dsh.api.dto.ModelValidationRequest;
import com.dsh.api.dto.Provider;
import com.dsh.api.dto.Usage;
import com.dsh.common.Digest;
import com.dsh.common.IdGen;
import com.dsh.storage.ModelProfileEntity;
import com.dsh.storage.ModelProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * 模型配置；真实端点验证与能力核验在 DSH-006 的 Gateway 中落地。
 * CONNECTIVITY 不发生成请求；成功的 TOOL_ROUNDTRIP 才能把配置摘要置为 VERIFIED。
 */
@Service
public class ModelProfileService {

    private final ModelProfileRepository repository;
    private final GatewayRouter gatewayRouter;
    private final ObjectMapper objectMapper;

    public ModelProfileService(ModelProfileRepository repository, GatewayRouter gatewayRouter,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.gatewayRouter = gatewayRouter;
        this.objectMapper = objectMapper;
    }

    public ModelProfile create(ModelProfileWrite request) {
        ModelOptions options = request == null ? null : request.options();
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name 必填且不能为空白");
        }
        if (request.name().length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name 长度不能超过 200");
        }
        requireProvider(request.provider());
        if (request.model() == null || request.model().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "model 必填");
        }
        requireBaseUrl(request.baseUrl());
        requireCredentialRef(request.credentialRef());
        requireOptions(options);
        checkThinkingConstraint(options);

        Instant now = Instant.now();
        ModelProfileEntity entity = new ModelProfileEntity(IdGen.next("prof_"), request.name(),
                request.provider().wire(), request.model(), request.baseUrl(), request.credentialRef(),
                options.thinking().name(),
                options.reasoningEffort() == null ? null : options.reasoningEffort().name(),
                options.maxOutputTokens(), options.streaming(), CapabilityStatus.UNKNOWN.name(), now, now);
        return toDto(repository.save(entity));
    }

    public ModelProfile get(String profileId) {
        return toDto(load(profileId));
    }

    public List<ModelProfile> listOrdered() {
        return repository.findAll().stream()
                .sorted(java.util.Comparator.comparing(ModelProfileEntity::getCreatedAt)
                        .thenComparing(ModelProfileEntity::getId))
                .map(ModelProfileService::toDto)
                .toList();
    }

    public ModelProfile patch(String profileId, ModelProfilePatch patch) {
        ModelProfileEntity entity = load(profileId);
        if (patch == null || (patch.name() == null && patch.provider() == null && patch.model() == null
                && patch.baseUrl() == null && patch.credentialRef() == null && patch.options() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "至少提供一个待更新字段");
        }
        boolean technicalChange = false;
        if (patch.name() != null) {
            if (patch.name().isBlank() || patch.name().length() > 200) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "name 长度必须在 1~200 之间");
            }
            entity.setName(patch.name());
        }
        if (patch.provider() != null) {
            requireProvider(patch.provider());
            if (!patch.provider().wire().equals(entity.getProvider())) {
                entity.setProvider(patch.provider().wire());
                technicalChange = true;
            }
        }
        if (patch.model() != null) {
            if (patch.model().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "model 不能为空白");
            }
            if (!patch.model().equals(entity.getModel())) {
                entity.setModel(patch.model());
                technicalChange = true;
            }
        }
        if (patch.baseUrl() != null) {
            requireBaseUrl(patch.baseUrl());
            if (!patch.baseUrl().equals(entity.getBaseUrl())) {
                entity.setBaseUrl(patch.baseUrl());
                technicalChange = true;
            }
        }
        if (patch.credentialRef() != null) {
            requireCredentialRef(patch.credentialRef());
            if (!patch.credentialRef().equals(entity.getCredentialRef())) {
                entity.setCredentialRef(patch.credentialRef());
                technicalChange = true;
            }
        }
        if (patch.options() != null) {
            requireOptions(patch.options());
            checkThinkingConstraint(patch.options());
            if (!sameOptions(entity, patch.options())) {
                applyOptions(entity, patch.options());
                technicalChange = true;
            }
        }
        if (technicalChange) {
            // 修改 model/baseUrl/provider/options/credentialRef 使能力状态回到 UNKNOWN
            entity.setCapabilityStatus(CapabilityStatus.UNKNOWN.name());
        }
        entity.setUpdatedAt(Instant.now());
        return toDto(repository.save(entity));
    }

    /**
     * 真实端点验证：
     * CONNECTIVITY — 最小请求（无工具），响应 finish_reason=stop 即通过；
     * TOOL_ROUNDTRIP — 挂载 echo 工具，首轮 tool_calls，回填工具结果后第二轮 stop 才通过；
     * 成功的 TOOL_ROUNDTRIP 将 capabilityStatus 置为 VERIFIED。
     */
    public ModelValidation validate(String profileId, ModelValidationRequest request) {
        if (request == null || request.level() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "level 必填");
        }
        ModelProfileEntity entity = load(profileId);
        CancellationToken token = new CancellationToken();
        ModelValidation.Level level = ModelValidation.Level.valueOf(request.level().name());

        if (request.level() == ModelValidationRequest.Level.CONNECTIVITY) {
            return validateConnectivity(entity, token, level);
        }
        return validateToolRoundtrip(entity, token, level);
    }

    private ModelValidation validateConnectivity(ModelProfileEntity entity, CancellationToken token,
                                                  ModelValidation.Level level) {
        try {
            ModelRequest req = baseRequest(entity, List.of(ModelMessage.user("ping")), List.of());
            ModelResponse resp = gatewayRouter.gatewayFor(entity.getProvider()).generate(req, token);
            boolean passed = resp.finishReason() != null
                    && !"PROTOCOL_ERROR".equals(resp.finishReason());
            String message = passed
                    ? "连通性验证通过: finish_reason=" + resp.finishReason()
                    : "连通性验证失败: finish_reason=" + resp.finishReason();
            return new ModelValidation(entity.getId(), level, passed, Instant.now(),
                    List.of(new ModelValidationCheck("CONNECTIVITY", passed, message)),
                    resp.usage() == null ? Usage.unknown() : resp.usage());
        } catch (ModelGatewayException e) {
            return new ModelValidation(entity.getId(), level, false, Instant.now(),
                    List.of(new ModelValidationCheck("CONNECTIVITY", false, "网关错误: " + e.getMessage())),
                    Usage.unknown());
        }
    }

    private ModelValidation validateToolRoundtrip(ModelProfileEntity entity, CancellationToken token,
                                                   ModelValidation.Level level) {
        try {
            ToolDefinition echo = echoTool();
            List<ModelMessage> messages = List.of(
                    ModelMessage.user("请调用 echo 工具，参数 text 为 hello"));
            ModelRequest first = baseRequest(entity, messages, List.of(echo));
            ModelResponse firstResp = gatewayRouter.gatewayFor(entity.getProvider()).generate(first, token);

            if (!"TOOL_CALLS".equals(firstResp.finishReason())
                    || firstResp.toolCalls() == null || firstResp.toolCalls().isEmpty()) {
                return new ModelValidation(entity.getId(), level, false, Instant.now(),
                        List.of(new ModelValidationCheck("TOOL_ROUNDTRIP", false,
                                "首轮未返回 tool_calls，finish_reason=" + firstResp.finishReason())),
                        Usage.unknown());
            }

            ModelToolCall call = firstResp.toolCalls().get(0);
            String toolResult = "{\"echoed\":\"hello\"}";
            List<ModelMessage> secondMessages = List.of(
                    ModelMessage.user("请调用 echo 工具，参数 text 为 hello"),
                    ModelMessage.assistantToolCalls(firstResp.toolCalls(), firstResp.reasoningContent()),
                    ModelMessage.toolResult(call.callId(), call.name(), toolResult));
            ModelRequest second = baseRequest(entity, secondMessages, List.of(echo));
            ModelResponse secondResp = gatewayRouter.gatewayFor(entity.getProvider()).generate(second, token);

            boolean passed = "STOP".equals(secondResp.finishReason());
            String message = passed
                    ? "工具协议验证通过: 首轮 tool_calls，回填后 stop"
                    : "工具协议验证失败: 第二轮 finish_reason=" + secondResp.finishReason();

            if (passed) {
                entity.setCapabilityStatus(CapabilityStatus.VERIFIED.name());
                entity.setUpdatedAt(Instant.now());
                repository.save(entity);
            }
            return new ModelValidation(entity.getId(), level, passed, Instant.now(),
                    List.of(new ModelValidationCheck("TOOL_ROUNDTRIP", passed, message)),
                    secondResp.usage() == null ? Usage.unknown() : secondResp.usage());
        } catch (ModelGatewayException e) {
            return new ModelValidation(entity.getId(), level, false, Instant.now(),
                    List.of(new ModelValidationCheck("TOOL_ROUNDTRIP", false, "网关错误: " + e.getMessage())),
                    Usage.unknown());
        }
    }

    private ModelRequest baseRequest(ModelProfileEntity entity, List<ModelMessage> messages,
                                      List<ToolDefinition> tools) {
        return new ModelRequest(
                entity.getProvider(), entity.getModel(), messages,
                entity.getOptionsMaxOutputTokens(), entity.getOptionsThinking(),
                tools, entity.getCredentialRef(), entity.getBaseUrl(), entity.isOptionsStreaming(), 0, null);
    }

    private ToolDefinition echoTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode text = properties.putObject("text");
        text.put("type", "string");
        text.put("description", "要回显的文本");
        schema.putArray("required").add("text");
        return new ToolDefinition("echo", "回显输入的文本", "1.0", "utility", "READ_ONLY", schema);
    }

    public ModelProfileEntity load(String profileId) {
        return repository.findById(profileId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "模型配置不存在"));
    }

    private void requireProvider(Provider provider) {
        if (provider == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "provider 必填");
        }
    }

    private void requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "baseUrl 必填");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "baseUrl 必须是 http(s) 地址");
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "baseUrl 不是合法 URI");
        }
    }

    private void requireCredentialRef(String credentialRef) {
        if (credentialRef != null && (credentialRef.isBlank() || credentialRef.length() > 200)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "credentialRef 长度必须在 1~200 之间");
        }
    }

    private void requireOptions(ModelOptions options) {
        if (options == null || options.thinking() == null || options.maxOutputTokens() == null
                || options.streaming() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "options 必填，且 thinking/maxOutputTokens/streaming 不可缺省");
        }
        if (options.maxOutputTokens() < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "maxOutputTokens 必须 >= 1");
        }
    }

    /** thinking=disabled 时 reasoningEffort 必须为 null（跨字段业务约束）。 */
    private void checkThinkingConstraint(ModelOptions options) {
        if (options.thinking() == ModelOptions.Thinking.disabled && options.reasoningEffort() != null) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "thinking=disabled 时 reasoningEffort 必须为 null");
        }
    }

    private static boolean sameOptions(ModelProfileEntity entity, ModelOptions options) {
        String effort = options.reasoningEffort() == null ? null : options.reasoningEffort().name();
        return entity.getOptionsThinking().equals(options.thinking().name())
                && java.util.Objects.equals(entity.getOptionsReasoningEffort(), effort)
                && entity.getOptionsMaxOutputTokens() == options.maxOutputTokens()
                && entity.isOptionsStreaming() == options.streaming();
    }

    private static void applyOptions(ModelProfileEntity entity, ModelOptions options) {
        entity.setOptionsThinking(options.thinking().name());
        entity.setOptionsReasoningEffort(
                options.reasoningEffort() == null ? null : options.reasoningEffort().name());
        entity.setOptionsMaxOutputTokens(options.maxOutputTokens());
        entity.setOptionsStreaming(options.streaming());
    }

    public static ModelProfile toDto(ModelProfileEntity entity) {
        return new ModelProfile(entity.getId(), entity.getName(), Provider.fromWire(entity.getProvider()),
                entity.getModel(), entity.getBaseUrl(), entity.getCredentialRef(),
                new ModelOptions(ModelOptions.Thinking.valueOf(entity.getOptionsThinking()),
                        entity.getOptionsReasoningEffort() == null
                                ? null
                                : ModelOptions.ReasoningEffort.valueOf(entity.getOptionsReasoningEffort()),
                        entity.getOptionsMaxOutputTokens(), entity.isOptionsStreaming()),
                CapabilityStatus.valueOf(entity.getCapabilityStatus()), entity.getVersion() + 1,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    public static String configDigest(ModelProfileEntity entity) {
        return Digest.sha256Hex(String.join("|",
                entity.getProvider(), entity.getModel(), entity.getBaseUrl(),
                entity.getCredentialRef() == null ? "" : entity.getCredentialRef(),
                entity.getOptionsThinking(),
                entity.getOptionsReasoningEffort() == null ? "" : entity.getOptionsReasoningEffort(),
                String.valueOf(entity.getOptionsMaxOutputTokens()),
                String.valueOf(entity.isOptionsStreaming())));
    }
}
