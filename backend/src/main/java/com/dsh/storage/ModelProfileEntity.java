package com.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** 模型配置；credentialRef 只引用 CredentialStore 条目名，不保存明文 Key。 */
@Entity
@Table(name = "model_profiles")
public class ModelProfileEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 200)
    private String model;

    @Column(nullable = false, length = 500)
    private String baseUrl;

    @Column(length = 200)
    private String credentialRef;

    @Column(nullable = false, length = 16)
    private String optionsThinking;

    @Column(length = 8)
    private String optionsReasoningEffort;

    @Column(nullable = false)
    private int optionsMaxOutputTokens;

    @Column(nullable = false)
    private boolean optionsStreaming;

    @Column(nullable = false, length = 16)
    private String capabilityStatus;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ModelProfileEntity() {
    }

    public ModelProfileEntity(String id, String name, String provider, String model, String baseUrl,
                              String credentialRef, String optionsThinking, String optionsReasoningEffort,
                              int optionsMaxOutputTokens, boolean optionsStreaming, String capabilityStatus,
                              Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.credentialRef = credentialRef;
        this.optionsThinking = optionsThinking;
        this.optionsReasoningEffort = optionsReasoningEffort;
        this.optionsMaxOutputTokens = optionsMaxOutputTokens;
        this.optionsStreaming = optionsStreaming;
        this.capabilityStatus = capabilityStatus;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public String getOptionsThinking() {
        return optionsThinking;
    }

    public void setOptionsThinking(String optionsThinking) {
        this.optionsThinking = optionsThinking;
    }

    public String getOptionsReasoningEffort() {
        return optionsReasoningEffort;
    }

    public void setOptionsReasoningEffort(String optionsReasoningEffort) {
        this.optionsReasoningEffort = optionsReasoningEffort;
    }

    public int getOptionsMaxOutputTokens() {
        return optionsMaxOutputTokens;
    }

    public void setOptionsMaxOutputTokens(int optionsMaxOutputTokens) {
        this.optionsMaxOutputTokens = optionsMaxOutputTokens;
    }

    public boolean isOptionsStreaming() {
        return optionsStreaming;
    }

    public void setOptionsStreaming(boolean optionsStreaming) {
        this.optionsStreaming = optionsStreaming;
    }

    public String getCapabilityStatus() {
        return capabilityStatus;
    }

    public void setCapabilityStatus(String capabilityStatus) {
        this.capabilityStatus = capabilityStatus;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
