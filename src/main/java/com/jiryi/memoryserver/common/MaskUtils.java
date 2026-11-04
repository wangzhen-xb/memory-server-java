package com.jiryi.memoryserver.common;

/**
 * 日志脱敏工具：绝不允许密码、完整 token 落日志（安全红线）
 */
public final class MaskUtils {

    /** 脱敏后保留的头尾长度 */
    private static final int KEEP = 4;

    private MaskUtils() {
    }

    /** token 只留前 4 位用于链路定位，后段一律打星 */
    public static String maskToken(String token) {
        if (token == null || token.length() <= KEEP) {
            return "****";
        }
        return token.substring(0, KEEP) + "****";
    }

    /** 用户名保留原文（非强敏感），但截断防超长日志 */
    public static String maskUsername(String username) {
        if (username == null || username.isEmpty()) {
            return "";
        }
        return username.length() <= 2 ? username.charAt(0) + "*" : username;
    }
}
