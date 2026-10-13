package com.dsh.api;

import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.event.EventPage;
import com.dsh.application.ThreadService;
import com.dsh.application.event.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * 事件 REST 补拉（事件协议 §4，openapi operationId=listEvents）。
 * WebSocket 推送属于后续阶段；本端点保证断线后序列与 eventId 可完整复现。
 */
@RestController
@RequestMapping("/api/v1")
public class EventController {

    private static final Pattern SEQUENCE = Pattern.compile("^(0|[1-9][0-9]*)$");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ThreadService threadService;
    private final EventStore eventStore;

    public EventController(ThreadService threadService, EventStore eventStore) {
        this.threadService = threadService;
        this.eventStore = eventStore;
    }

    @GetMapping("/threads/{threadId}/events")
    public EventPage list(@PathVariable String threadId,
                          @RequestParam(name = "afterSequence", required = false, defaultValue = "0")
                          String afterSequence,
                          @RequestParam(name = "throughSequence", required = false)
                          String throughSequence,
                          @RequestParam(name = "limit", required = false) Integer limit) {
        threadService.load(threadId);
        long after = parseCursor(afterSequence, "afterSequence");
        Long through = null;
        if (throughSequence != null && !throughSequence.isBlank()) {
            through = parseCursor(throughSequence, "throughSequence");
        }
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new ApiException(ErrorCode.INVALID_CURSOR, "limit 必须在 1~200 之间");
        }
        return eventStore.readPage(threadId, after, through, pageSize);
    }

    private long parseCursor(String value, String name) {
        if (value == null || !SEQUENCE.matcher(value).matches()) {
            throw new ApiException(ErrorCode.INVALID_CURSOR,
                    name + " 必须是十进制非负整数，且不含前导 0");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_CURSOR, name + " 超出数值范围");
        }
    }
}
