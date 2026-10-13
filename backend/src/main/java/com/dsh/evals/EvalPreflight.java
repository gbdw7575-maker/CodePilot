package com.dsh.evals;

import com.dsh.application.DshProperties;
import com.dsh.evals.model.EvalConfig;
import com.dsh.evals.model.EvalStrategy;
import org.springframework.stereotype.Component;

/** 真实评测 CLI 的执行因子检查；在创建项目、提交 Run 或调用模型前失败。 */
@Component
public class EvalPreflight {
    private final DshProperties properties;

    public EvalPreflight(DshProperties properties) { this.properties = properties; }

    public void validate(EvalConfig config) {
        if (properties.orchestration().enabled() != (config.strategy() == EvalStrategy.MULTI)) {
            throw new IllegalArgumentException("strategy 与 dsh.orchestration.enabled 不一致");
        }
        if (properties.loop().synchronous()) {
            throw new IllegalArgumentException("真实评测要求 dsh.loop.synchronous=false，保证等待及截止时间可被监督");
        }
        if (!"docker".equals(properties.sandbox().mode())) {
            throw new IllegalArgumentException("真实评测要求 Docker 沙箱");
        }
        String digest = config.imageDigest();
        String image = properties.sandbox().image();
        if (!digest.matches("sha256:[0-9a-f]{64}")
                || !(digest.equals(image) || image.endsWith("@" + digest))) {
            throw new IllegalArgumentException("dsh.sandbox.image 必须固定为冻结 imageDigest，不能使用可变 tag");
        }
        if (config.seed() != null) {
            throw new IllegalArgumentException("当前 Gateway 未接入评测 seed，不能声明已执行非空冻结 seed");
        }
    }
}
