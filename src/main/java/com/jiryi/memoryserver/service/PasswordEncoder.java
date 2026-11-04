package com.jiryi.memoryserver.service;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 密码哈希服务：兼容存量 Node crypto.scryptSync 格式，新增保留可升级空间
 *
 * 存量格式（app.db 已写入的数据）：saltHex:hashHex，参数 scrypt(N=16384, r=8, p=1, dkLen=64)
 * 新格  式：{scrypt}saltHex:hashHex，登录校验通过后由 AuthService 透明升级
 * 依赖 BouncyCastle 的标准 scrypt（RFC 7914），输出与 OpenSSL/Node 逐字节一致，
 * 不自写 SMix，避免算法实现偏差导致存量账号全部登录失败。
 */
@Slf4j
@Service
public class PasswordEncoder {

    /** 新格式标识前缀 */
    private static final String PREFIX = "{scrypt}";

    private static final String HEX_ONLY = "^[0-9a-fA-F]+$";

    /** JDK 内置 hex 编解码：输出小写，与存量 Node scrypt 格式逐字节一致 */
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random = new SecureRandom();

    /**
     * 生成 saltHex:hashHex（与存量同构，便于统一策略）
     *
     * @param n       CPU/内存成本参数
     * @param r       块大小因子
     * @param p       并行因子
     * @param dkLen   派生长度
     * @param saltLen 盐字节数
     */
    public String encode(String raw, int n, int r, int p, int dkLen, int saltLen) {
        byte[] salt = new byte[saltLen];
        random.nextBytes(salt);
        byte[] hash = scrypt(raw, salt, n, r, p, dkLen);
        return HEX.formatHex(salt) + ":" + HEX.formatHex(hash);
    }

    /**
     * 校验：自动识别有无 {scrypt} 前缀，两种格式统一按 saltHex:hashHex 解析
     */
    public boolean matches(String raw, String stored, int n, int r, int p, int dkLen) {
        if (raw == null || raw.isEmpty() || stored == null || stored.isEmpty()) {
            return false;
        }
        String body = stored.startsWith(PREFIX) ? stored.substring(PREFIX.length()) : stored;
        int idx = body.indexOf(':');
        if (idx <= 0 || idx == body.length() - 1) {
            // 格式异常只报前 3 个字符，绝不落完整哈希
            log.warn("密码存储格式异常, head={}", body.substring(0, Math.min(3, body.length())));
            return false;
        }
        String saltHex = body.substring(0, idx);
        String hashHex = body.substring(idx + 1);
        if (!saltHex.matches(HEX_ONLY) || !hashHex.matches(HEX_ONLY)) {
            log.warn("密码存储格式非法hex, head={}", body.substring(0, Math.min(3, body.length())));
            return false;
        }
        byte[] expect = HEX.parseHex(hashHex);
        byte[] actual = scrypt(raw, HEX.parseHex(saltHex), n, r, p, expect.length);
        // 定长比较，防时序侧信道
        return MessageDigest.isEqual(expect, actual);
    }

    /** 是否为待升级的存量格式（无前缀） */
    public boolean isLegacy(String stored) {
        return stored != null && !stored.startsWith(PREFIX);
    }

    /** 升级为带前缀格式，登录成功时顺带改写 */
    public String upgrade(String stored) {
        return PREFIX + stored;
    }

    /** scrypt 计算：PBKDF2-SHA256(1 次) -> SMix -> PBKDF2-SHA256(1 次)，与 RFC 7914 一致 */
    private byte[] scrypt(String raw, byte[] salt, int n, int r, int p, int dkLen) {
        return SCrypt.generate(raw.getBytes(StandardCharsets.UTF_8), salt, n, r, p, dkLen);
    }
}
