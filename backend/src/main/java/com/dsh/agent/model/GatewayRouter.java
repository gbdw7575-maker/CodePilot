package com.dsh.agent.model;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按冻结配置的 provider 选择网关；DSH-003 只接入 fake，其余供应商明确失败。 */
@Component
public class GatewayRouter {

    private final Map<String, ModelGateway> gateways;

    public GatewayRouter(List<ModelGateway> gatewayBeans) {
        this.gateways = new HashMap<>();
        for (ModelGateway gateway : gatewayBeans) {
            gateways.put(gateway.provider(), gateway);
        }
    }

    public ModelGateway gatewayFor(String provider) {
        ModelGateway gateway = gateways.get(provider);
        if (gateway == null) {
            throw new ModelGatewayException("模型供应商尚未接入: " + provider);
        }
        return gateway;
    }
}
