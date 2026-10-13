package com.dsh.api;

import com.dsh.api.dto.ErrorCode;

/**
 * ETag 形如 "v3"（含引号）。缺失 428，格式错误（含通配符 *）400，版本不符 412。
 */
public final class Etags {

    private Etags() {
    }

    public static String of(long version) {
        return "\"v" + version + "\"";
    }

    public static void check(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ErrorCode.PRECONDITION_REQUIRED, "缺少 If-Match 头，请先 GET 资源获取 ETag");
        }
        long expected = parse(ifMatch);
        if (expected != currentVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "资源版本已变化，请重新读取后再试");
        }
    }

    private static long parse(String ifMatch) {
        String value = ifMatch.trim();
        if (!value.matches("\"v[1-9][0-9]*\"")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "If-Match 必须是形如 \"v3\" 的 ETag，不接受通配符 *");
        }
        return Long.parseLong(value.substring(2, value.length() - 1));
    }
}
