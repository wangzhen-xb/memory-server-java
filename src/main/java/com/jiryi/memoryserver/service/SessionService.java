package com.jiryi.memoryserver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jiryi.memoryserver.common.MaskUtils;
import com.jiryi.memoryserver.common.TokenHasher;
import com.jiryi.memoryserver.config.MemoryProperties;
import com.jiryi.memoryserver.entity.SessionPO;
import com.jiryi.memoryserver.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 会话服务：Redis 为主存储，DB 流水用于留痕与 Redis 数据丢失时回落
 *
 * 回落策略：Redis 查不到时读 t_session 并校验 expires_at，命中则回填 Redis，
 * 这样 Redis 重启/被清空不会导致全体用户掉线（数据来自带 AOF 的 PG）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionMapper sessionMapper;

    private final RedisSessionStore redisStore;

    private final MemoryProperties props;

    /** token 必须用安全随机源，Random 可被预测 */
    private final SecureRandom random = new SecureRandom();

    /** JDK 内置小写 hex 格式化，与存量 token 字面格式一致 */
    private static final HexFormat HEX = HexFormat.of();

    /**
     * 创建会话：先落 DB（强一致留痕），事务提交后再写 Redis（鉴权快路径）
     *
     * 明文 token 只在返回值中出现一次；DB 与 Redis 一律只存 SHA-256 摘要，
     * 这样即使数据库被拖库，也无法直接拿去冒用登录态。
     *
     * @return 明文 token，仅此一次返回给客户端
     */
    @Transactional(rollbackFor = Exception.class)
    public String create(Long userId) {
        String token = newToken();
        String hashed = TokenHasher.sha256Hex(token);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(props.getSessionTtl());

        SessionPO po = new SessionPO();
        po.setToken(hashed);
        po.setUserId(userId);
        po.setExpiresAt(expiresAt);
        sessionMapper.insert(po);

        // Redis 写在事务提交之后：回滚时不会留下一个"DB 没有、Redis 有"的孤儿会话
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisStore.saveSession(hashed, userId);
                }
            });
        } else {
            redisStore.saveSession(hashed, userId);
        }
        log.info("创建会话 userId={}, token={}", userId, MaskUtils.maskToken(token));
        return token;
    }

    /**
     * 解析 token -> userId
     *
     * 查找顺序：摘要 Redis → 明文 Redis（存量兼容）→ 摘要 DB → 明文 DB（兼容并原地升级）
     * @return 空表示未登录或已过期；过期会话会被顺手清理
     */
    public Optional<Long> resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hashed = TokenHasher.sha256Hex(token);
        Optional<Long> cached = redisStore.readSession(hashed);
        if (cached.isPresent()) {
            return cached;
        }
        // 存量兼容：升级前的会话键仍是明文 token，命中后仅返回，随 TTL 自然淘汰
        if (!hashed.equals(token)) {
            Optional<Long> legacyCached = redisStore.readSession(token);
            if (legacyCached.isPresent()) {
                return legacyCached;
            }
        }
        SessionPO po = selectByToken(hashed);
        if (po == null && !hashed.equals(token)) {
            // 存量兼容：DB 行 token 列仍是明文，命中后原地改写为摘要并回填 Redis
            po = selectByToken(token);
            if (po != null) {
                upgradeLegacyToken(token, hashed, po);
            }
        }
        if (po == null) {
            return Optional.empty();
        }
        if (po.getExpiresAt() == null || po.getExpiresAt().isBefore(OffsetDateTime.now())) {
            destroy(token);
            return Optional.empty();
        }
        // 回填 Redis：剩余有效期按原过期时间折算，不能重新给满 30 天
        long remainSeconds = Duration.between(OffsetDateTime.now(), po.getExpiresAt()).getSeconds();
        if (remainSeconds > 0) {
            redisStore.saveSession(hashed, po.getUserId(), remainSeconds);
            log.info("会话从 DB 回落并回填, token={}", MaskUtils.maskToken(token));
        }
        return Optional.ofNullable(po.getUserId());
    }

    /** 登出：删 Redis + 删 DB 流水；摘要键与存量明文键都要清，避免退出后残留 */
    public void destroy(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String hashed = TokenHasher.sha256Hex(token);
        redisStore.removeSession(hashed);
        if (!hashed.equals(token)) {
            redisStore.removeSession(token);
        }
        sessionMapper.delete(Wrappers.<SessionPO>lambdaQuery()
                .eq(SessionPO::getToken, hashed)
                .or()
                .eq(SessionPO::getToken, token));
    }

    private SessionPO selectByToken(String tokenValue) {
        return sessionMapper.selectOne(Wrappers.<SessionPO>lambdaQuery()
                .select(SessionPO::getUserId, SessionPO::getExpiresAt)
                .eq(SessionPO::getToken, tokenValue));
    }

    /**
     * 存量明文 token 升级为摘要：先改 DB 主键，再补写摘要 Redis 键
     *
     * 明文 Redis 键不主动删除，让它随 TTL 自然过期——正在用旧 token 的其它端
     * 仍可能在本次请求之后继续调用，提前删除会造成偶发掉线。
     */
    private void upgradeLegacyToken(String plain, String hashed, SessionPO po) {
        sessionMapper.update(null, Wrappers.<SessionPO>lambdaUpdate()
                .set(SessionPO::getToken, hashed)
                .eq(SessionPO::getToken, plain));
        long remainSeconds = po.getExpiresAt() == null
                ? 0
                : Duration.between(OffsetDateTime.now(), po.getExpiresAt()).getSeconds();
        if (remainSeconds > 0) {
            redisStore.saveSession(hashed, po.getUserId(), remainSeconds);
        }
        log.info("存量明文 token 已升级为摘要, token={}", MaskUtils.maskToken(plain));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
}
