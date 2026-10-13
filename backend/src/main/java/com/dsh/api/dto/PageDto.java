package com.dsh.api.dto;

import java.util.List;

/** 普通列表分页结构，对应 openapi.json 的 *Page Schema：{items, nextCursor}。 */
public record PageDto<T>(List<T> items, String nextCursor) {
}
