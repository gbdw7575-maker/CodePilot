package com.dsh.api;

import com.dsh.agent.model.CompositeCredentialStore;
import com.dsh.api.dto.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地凭据管理（本地开发工具端点，不在 OpenAPI 契约内）：
 * 运行时保存/查询 API Key，免去重启与环境变量配置。
 * GET 只返回条目名与是否本地存在，永不返回明文 Key。
 */
@RestController
@RequestMapping("/api/v1/credentials")
public class CredentialController {

    private final CompositeCredentialStore store;

    public CredentialController(CompositeCredentialStore store) {
        this.store = store;
    }

    public record SaveCredentialRequest(String apiKey) {
    }

    public record CredentialStatus(String ref, boolean local) {
    }

    public record CredentialList(List<CredentialStatus> items) {
    }

    @PutMapping("/{credentialRef}")
    public Map<String, Object> save(@PathVariable String credentialRef,
                                    @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key",
                                            required = false) String idempotencyKey,
                                    @RequestBody SaveCredentialRequest request) {
        if (credentialRef == null || !credentialRef.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "credentialRef 仅允许字母/数字/下划线/连字符，长度 1~64");
        }
        if (request == null || request.apiKey() == null || request.apiKey().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "apiKey 必填");
        }
        if (request.apiKey().length() > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "apiKey 长度不能超过 1000");
        }
        store.save(credentialRef, request.apiKey().trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", credentialRef);
        body.put("saved", true);
        return body;
    }

    @GetMapping
    public CredentialList list() {
        List<String> refs = store.localRefs();
        List<CredentialStatus> items = refs.stream().map(ref -> new CredentialStatus(ref, true)).toList();
        return new CredentialList(items);
    }
}
