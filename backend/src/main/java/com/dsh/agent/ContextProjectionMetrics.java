package com.dsh.agent;

import com.dsh.agent.model.ModelMessage;
import java.util.List;

/** Numeric diagnostics only; raw estimates use TokenEstimator, not a supplier tokenizer. */
public record ContextProjectionMetrics(
        int replayProtectedTailUnits, String compactionMode,
        int historyMessagesBeforeReplay, int historyMessagesAfterReplay, int replayFoldedResults,
        int historyRawTokensBeforeReplay, int historyRawTokensAfterReplay,
        int historyMessagesFinal, int historyRawTokensFinal,
        int systemRawTokensFinal, int toolsRawTokensFinal,
        int toolCallsRawTokensFinal, int toolResultsRawTokensFinal, int otherHistoryRawTokensFinal,
        int estimatedInputTokens, int reservedOutputTokens,
        boolean windowPressure, boolean watermarkPressure, boolean cumulativePressure) {

    record ReplayStage(int beforeMessages, int afterMessages, int foldedResults, int beforeTokens, int afterTokens) {
        static ReplayStage capture(List<ModelMessage> before, List<ModelMessage> after) {
            int folded = 0;
            for (int i = 0; i < before.size(); i++) {
                if ("tool".equals(before.get(i).role()) && before.get(i) != after.get(i)) folded++;
            }
            return new ReplayStage(before.size(), after.size(), folded,
                    TokenEstimator.estimateMessages(before), TokenEstimator.estimateMessages(after));
        }
    }

    static ContextProjectionMetrics finish(ReplayStage replay, int tail, String mode,
            List<ModelMessage> finalMessages, int toolsTokens, int estimatedInput, int reservedOutput,
            boolean window, boolean watermark, boolean cumulative) {
        var history = finalMessages.subList(1, finalMessages.size());
        int calls = 0, results = 0, other = 0;
        for (var message : history) {
            int content = TokenEstimator.estimateText(message.content());
            if ("tool".equals(message.role())) results += content;
            else other += content;
            if (message.toolCalls() != null) {
                for (var call : message.toolCalls())
                    calls += TokenEstimator.estimateText(call.name()) + TokenEstimator.estimateText(call.argumentsJson());
            }
        }
        return new ContextProjectionMetrics(tail, mode, replay.beforeMessages(), replay.afterMessages(),
                replay.foldedResults(), replay.beforeTokens(), replay.afterTokens(),
                history.size(), TokenEstimator.estimateMessages(history),
                TokenEstimator.estimateMessage(finalMessages.getFirst()), toolsTokens,
                calls, results, other, estimatedInput, reservedOutput, window, watermark, cumulative);
    }
}
