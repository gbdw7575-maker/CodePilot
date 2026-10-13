package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import com.dsh.agent.model.ModelToolCall;
import java.util.*;
import java.util.function.UnaryOperator;

/** C23: change only old result views; retain calls, user text and complete batch boundaries. */
final class ToolOutputReplay {
    private ToolOutputReplay() {}

    static List<ModelMessage> fold(List<ModelMessage> history, int protectedTailUnits,
                                   UnaryOperator<ModelMessage> candidate) {
        if (protectedTailUnits <= 0) return history;
        List<List<ModelMessage>> units = new ArrayList<>();
        for (int i = 0; i < history.size();) {
            ModelMessage head = history.get(i++);
            List<ModelMessage> unit = new ArrayList<>();
            unit.add(head);
            if (head.toolCalls() != null && !head.toolCalls().isEmpty()) {
                while (i < history.size() && "tool".equals(history.get(i).role()))
                    unit.add(history.get(i++));
            }
            units.add(unit);
        }
        List<ModelMessage> result = new ArrayList<>(history.size());
        int boundary = Math.max(0, units.size() - protectedTailUnits);
        for (int i = 0; i < units.size(); i++) {
            List<ModelMessage> unit = units.get(i);
            boolean eligible = i < boundary && paired(unit);
            for (ModelMessage original : unit) {
                ModelMessage folded = eligible && "tool".equals(original.role())
                        ? candidate.apply(original) : original;
                // A projection must keep the result identity and actually reduce estimated input cost.
                if (folded == null || !Objects.equals(original.role(), folded.role())
                        || !Objects.equals(original.toolCallId(), folded.toolCallId())
                        || !Objects.equals(original.toolName(), folded.toolName())
                        || !Objects.equals(original.toolCalls(), folded.toolCalls())
                        || !Objects.equals(original.reasoningContent(), folded.reasoningContent())
                        || TokenEstimator.estimateMessage(folded) >= TokenEstimator.estimateMessage(original))
                    folded = original;
                result.add(folded);
            }
        }
        return result;
    }

    private static boolean paired(List<ModelMessage> unit) {
        ModelMessage head = unit.getFirst();
        if (!"assistant".equals(head.role()) || head.toolCalls() == null || head.toolCalls().isEmpty())
            return false;
        Map<String, String> expected = new HashMap<>();
        for (ModelToolCall call : head.toolCalls()) {
            if (call.callId() == null || call.callId().isBlank() || call.name() == null
                    || expected.putIfAbsent(call.callId(), call.name()) != null) return false;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < unit.size(); i++) {
            ModelMessage m = unit.get(i);
            if (!"tool".equals(m.role()) || !seen.add(m.toolCallId())
                    || !Objects.equals(expected.get(m.toolCallId()), m.toolName())) return false;
        }
        return seen.equals(expected.keySet());
    }
}
