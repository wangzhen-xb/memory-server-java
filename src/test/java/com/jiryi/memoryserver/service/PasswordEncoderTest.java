package com.jiryi.memoryserver.service;

import org.bouncycastle.crypto.generators.SCrypt;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * 跨语言 scrypt 兼容性测试（迁移正确性的核心保障）
 *
 * 基准向量由 Node 生成：
 *   crypto.scryptSync('hello-memory', Buffer.from(SALT,'hex'), 64).toString('hex')
 * 参数：N=16384, r=8, p=1（Node 默认），dkLen=64，salt 16 字节。
 * 只要本测试通过，即可证明 Java(BouncyCastle) 与 Node 对同一 (密码,盐) 逐字节一致，
 * 存量 app.db 中的密码无需重置即可被新服务校验通过。
 */
class PasswordEncoderTest {

    private static final String SALT_HEX = "5d753fa0be877182768753140a716de6";
    private static final String PASSWORD = "hello-memory";
    private static final String NODE_HASH_HEX =
            "a7ed7c4208b71819bbeccc80f7aab8bb5c1fd153903a52323fed5fb0798c6cece5b846fbdd5120967d2eb0f3d3d30d72ca71153651c933d18afe90983c8e2174";

    private static final int N = 16384;
    private static final int R = 8;
    private static final int P = 1;
    private static final int DK_LEN = 64;

    @Test
    void bouncyCastleScryptMatchesNodeVector() {
        byte[] derived = SCrypt.generate(PASSWORD.getBytes(StandardCharsets.UTF_8),
                hexToBytes(SALT_HEX), N, R, P, DK_LEN);
        Assertions.assertEquals(NODE_HASH_HEX, toHex(derived),
                "BouncyCastle 与 Node scrypt 输出必须逐字节一致");
    }

    @Test
    void legacyFormatPasswordVerifies() {
        PasswordEncoder encoder = new PasswordEncoder();
        // 存量格式：saltHex:hashHex（无 {scrypt} 前缀）
        String stored = SALT_HEX + ":" + NODE_HASH_HEX;
        Assertions.assertTrue(encoder.matches(PASSWORD, stored, N, R, P, DK_LEN),
                "存量 legacy 格式应校验通过");
        Assertions.assertFalse(encoder.matches(PASSWORD + "x", stored, N, R, P, DK_LEN),
                "错误密码必须拒绝");
        Assertions.assertTrue(encoder.isLegacy(stored), "无前缀应识别为 legacy");
    }

    @Test
    void prefixedFormatAlsoVerifies() {
        PasswordEncoder encoder = new PasswordEncoder();
        String legacy = SALT_HEX + ":" + NODE_HASH_HEX;
        String upgraded = encoder.upgrade(legacy);
        Assertions.assertTrue(encoder.matches(PASSWORD, upgraded, N, R, P, DK_LEN),
                "{scrypt} 前缀格式升级后仍能校验");
        Assertions.assertFalse(encoder.isLegacy(upgraded), "带前缀不应再是 legacy");
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
