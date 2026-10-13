package com.dsh.evals.model;

import com.dsh.common.Digest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** E07: independent versioned identity; never recomputes legacy configDigest. */
public record EvalExecutionManifest(String version, String executionDigest, ObjectNode factors,
                                    List<String> limitations) {
    public static final String VERSION = "dsh-execution-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public EvalExecutionManifest {
        if (!VERSION.equals(version) || factors == null) throw new IllegalArgumentException("执行清单版本或因子无效");
        // Normalize JSON number node widths (LongNode(60) vs IntNode(60)) for round-trip equality.
        try { factors = (ObjectNode) MAPPER.readTree(factors.toString()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("执行因子不是有效 JSON", e); }
        limitations = limitations == null ? List.of() : limitations.stream().distinct().sorted().toList();
        if (!digest(factors, limitations).equals(executionDigest))
            throw new IllegalArgumentException("executionDigest 与执行清单不匹配");
    }
    @Override public ObjectNode factors() { return factors.deepCopy(); }
    public static EvalExecutionManifest create(ObjectNode factors, List<String> limitations) {
        var limits = limitations.stream().distinct().sorted().toList();
        return new EvalExecutionManifest(VERSION, digest(factors, limits), factors, limits);
    }
    private static String digest(ObjectNode factors, List<String> limits) {
        ObjectNode payload=MAPPER.createObjectNode();payload.put("version",VERSION);
        payload.set("factors",factors);payload.set("limitations",MAPPER.valueToTree(limits));
        return hashJson(payload);
    }
    /** Object key order is irrelevant, while array/execution order remains meaningful. */
    public static String hashJson(JsonNode value) {
        return Digest.sha256Hex(canonical(value).toString());
    }
    private static JsonNode canonical(JsonNode n) {
        if(n.isObject()) {
            ObjectNode out=MAPPER.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);
            for(String k:keys) out.set(k,canonical(n.get(k)));return out;
        }
        if(n.isArray()) {var out=MAPPER.createArrayNode();for(JsonNode v:n)out.add(canonical(v));return out;}
        return n;
    }
    public void validateFor(EvalConfig config) {
        ObjectNode expected=MAPPER.createObjectNode();expected.put("eval.strategy",config.strategy().name());
        expected.put("eval.datasetName",config.datasetName());expected.put("eval.datasetVersion",config.datasetVersion());
        expected.put("eval.split",config.split());expected.put("eval.modelProfileConfigDigest",config.modelProfileConfigDigest());
        expected.set("eval.budget",MAPPER.valueToTree(config.budget()));expected.put("eval.repeatCount",config.repeatCount());
        expected.set("eval.sampleIds",MAPPER.valueToTree(config.sampleIds().stream().sorted().toList()));
        if(config.seed()==null)expected.putNull("eval.seed");else expected.put("eval.seed",config.seed());
        expected.put("sandbox.declaredImageDigest",config.imageDigest());
        expected.put("orchestration.enabled",config.strategy()==EvalStrategy.MULTI);
        expected.fields().forEachRemaining(e->{if(!e.getValue().equals(factors.path(e.getKey())))
            throw new IllegalArgumentException("执行清单与配置归属不符："+e.getKey());});
    }
    public String comparisonFactorDigest() {
        ObjectNode common=factors();common.remove(List.of("eval.strategy","orchestration.enabled"));
        return digest(common,limitations);
    }
    /** New comparisons only tolerate the declared SINGLE/MULTI variable. */
    public static List<String> comparisonWarnings(EvalExecutionManifest a, EvalExecutionManifest b) {
        if(a==null && b==null) return List.of("LEGACY_EXECUTION_UNVERIFIED：历史报告无执行清单，仅校验旧配置因子，不证明运行条件完整一致");
        if(a==null || b==null) throw new IllegalArgumentException("新执行清单与历史报告不能混合对照");
        ObjectNode left=a.factors(),right=b.factors();
        left.remove(List.of("eval.strategy","orchestration.enabled"));
        right.remove(List.of("eval.strategy","orchestration.enabled"));
        if(!hashJson(left).equals(hashJson(right)) || !a.limitations().equals(b.limitations()))
            throw new IllegalArgumentException("执行清单存在策略以外的不同因子，拒绝对照");
        return a.limitations().stream().map(l->"EXECUTION_INCOMPLETE："+l).toList();
    }
}
