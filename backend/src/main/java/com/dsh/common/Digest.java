package com.dsh.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 确定性 SHA-256 摘要（小写十六进制），用于幂等请求摘要与冻结配置摘要。 */
public final class Digest {

    private Digest() {
    }

    public static String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256(input.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(byte[] input) {
        return HexFormat.of().formatHex(sha256(input));
    }

    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
