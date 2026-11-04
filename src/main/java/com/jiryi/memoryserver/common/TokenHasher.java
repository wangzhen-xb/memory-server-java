package com.jiryi.memoryserver.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * token 摘要工具：数据库与 Redis 只保存 SHA-256 摘要，明文仅在响应客户端时出现一次
 *
 * 为什么用 SHA-256 而不是 BCrypt：token 是 32 字节安全随机串（256 位熵），
 * 不存在被字典爆破的可能，无需慢哈希；SHA-256 使其在库中不可反推、不可直接复用。
 * 输出为 64 位小写 hex，与 t_session.token VARCHAR(64) 完全对齐。
 */
public final class TokenHasher {

    private static final HexFormat HEX = HexFormat.of();

    private TokenHasher() {
    }

    /** 返回 64 位小写十六进制摘要；入参为空时返回空串，交由调用方判空 */
    public static String sha256Hex(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备算法，缺失说明运行环境被裁剪，属于不可恢复的启动级错误
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
