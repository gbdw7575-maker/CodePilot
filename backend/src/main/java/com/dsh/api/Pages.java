package com.dsh.api;

import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.PageDto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** 普通列表分页：cursor + limit，默认 50、最大 200，nextCursor=null 表示结束。 */
public final class Pages {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private Pages() {
    }

    public record Query(int offset, int limit) {
    }

    public static Query parse(String cursor, Integer limit) {
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1 || effective > MAX_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit 必须在 1~200 之间");
        }
        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.INVALID_CURSOR, "cursor 无法解析");
            }
            if (offset < 0) {
                throw new ApiException(ErrorCode.INVALID_CURSOR, "cursor 无法解析");
            }
        }
        return new Query(offset, effective);
    }

    /** ordered 为按稳定顺序排好的全集，这里负责切片与游标。 */
    public static <T> PageDto<T> slice(List<T> ordered, Query query) {
        int from = Math.min(query.offset(), ordered.size());
        int to = Math.min(query.offset() + query.limit(), ordered.size());
        String nextCursor = to < ordered.size() ? encodeOffset(to) : null;
        return new PageDto<>(List.copyOf(ordered.subList(from, to)), nextCursor);
    }

    public static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }
}
