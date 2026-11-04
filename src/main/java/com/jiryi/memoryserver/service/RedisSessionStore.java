package com.jiryi.memoryserver.service;

import com.jiryi.memoryserver.common.MaskUtils;
import com.jiryi.memoryserver.config.MemoryProperties;
import com.jiryi.memoryserver.config.RedisKeyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

/**
 * Redis 统一封装：会话主存储 + 登录失败计数
 *
 * 设计要点：
 * 1. 所有方法吞掉 DataAccessException 并降级，Redis 抖动不能让已登录用户集体掉线；
 * 2. 读不到返回 Optional.empty()，调用方自行决定回落 DB；
 * 3. 键前缀外置，多环境共用实例时靠它隔离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSessionStore {

    /**
     * INCR + 首次 EXPIRE 必须原子，否则两步之间进程崩溃会留下无 TTL 的计数键，
     * 该账号将被永久锁定（Redis 重启后 COUNT 仍在、窗口已丢）
     */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('incr', KEYS[1])\n"
                    + "if c == 1 then redis.call('expire', KEYS[1], ARGV[1]) end\n"
                    + "return c", Long.class);

    /** 值固定为 1，只借键存在性表达"已锁定"，剩余时长取键 TTL，因此无解析异常分支 */
    private static final RedisScript<Long> SET_IF_ABSENT = new DefaultRedisScript<>(
            "if redis.call('set', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then return 1 else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    private final RedisKeyProperties keys;

    private final MemoryProperties props;

    /**
     * 写入会话：key = mem:sess:{token}，value = userId，TTL = 会话有效期
     */
    public void saveSession(String token, Long userId) {
        try {
            redis.opsForValue().set(keys.session(token), String.valueOf(userId), props.getSessionTtl());
        } catch (DataAccessException e) {
            log.warn("Redis 写会话失败，降级为仅 DB 留痕, token={}", mask(token), e);
        }
    }

    /** 按指定剩余秒数回写会话（DB 回落场景，不能重新给满一个 TTL） */
    public void saveSession(String token, Long userId, long ttlSeconds) {
        try {
            redis.opsForValue().set(keys.session(token), String.valueOf(userId), Duration.ofSeconds(ttlSeconds));
        } catch (DataAccessException e) {
            log.warn("Redis 回填会话失败, token={}", mask(token), e);
        }
    }

    /**
     * 读会话；Redis 异常与键不存在都返回 empty，由 SessionService 回落 DB
     */
    public Optional<Long> readSession(String token) {
        try {
            String value = redis.opsForValue().get(keys.session(token));
            if (value == null || value.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.valueOf(value));
            } catch (NumberFormatException e) {
                // 脏数据（早期版本写入的非数字值）：删掉并当作未登录，让用户重新登录即可
                log.warn("会话值非法，已清理, token={}", mask(token));
                redis.delete(keys.session(token));
                return Optional.empty();
            }
        } catch (DataAccessException e) {
            log.warn("Redis 读会话失败, token={}", mask(token), e);
            return Optional.empty();
        }
    }

    /** 删除会话（登出） */
    public void removeSession(String token) {
        try {
            redis.delete(keys.session(token));
        } catch (DataAccessException e) {
            log.warn("Redis 删会话失败, token={}", mask(token), e);
        }
    }

    // ==================== 登录失败计数与锁定 ====================

    /**
     * 累加失败次数；返回 -1 表示 Redis 不可用（调用方必须跳过锁定判定，
     * 不能因缓存故障把正常用户拒之门外）
     */
    public long increaseLoginFail(String username) {
        try {
            Long count = redis.execute(INCR_WITH_TTL,
                    Collections.singletonList(keys.loginFail(username)),
                    String.valueOf(props.getLoginFailLock().toSeconds()));
            return count == null ? -1L : count;
        } catch (DataAccessException e) {
            log.warn("Redis 登录计数失败, username={}", MaskUtils.maskUsername(username), e);
            return -1L;
        }
    }

    /**
     * 达到阈值则打锁定标记（SETNX + EX），返回是否写入成功
     *
     * @return true 表示本次触发锁定；false 表示此前已锁定或 Redis 不可用
     */
    public boolean markLoginLock(String username) {
        try {
            Long ok = redis.execute(SET_IF_ABSENT,
                    Collections.singletonList(keys.loginFailLock(username)),
                    String.valueOf(props.getLoginFailLock().toSeconds()));
            return ok != null && ok == 1L;
        } catch (DataAccessException e) {
            log.warn("Redis 写登录锁定失败, username={}", MaskUtils.maskUsername(username), e);
            return false;
        }
    }

    /** 锁定剩余秒数；-1 表示未锁定 */
    public long loginLockSeconds(String username) {
        try {
            Long ttl = redis.getExpire(keys.loginFailLock(username));
            return (ttl == null || ttl <= 0) ? -1L : ttl;
        } catch (DataAccessException e) {
            return -1L;
        }
    }

    /** 登录成功清空计数与锁定标记，防止历史失败影响下次判定 */
    public void clearLoginFail(String username) {
        try {
            redis.delete(keys.loginFail(username));
            redis.delete(keys.loginFailLock(username));
        } catch (DataAccessException e) {
            log.warn("Redis 清登录计数失败, username={}", MaskUtils.maskUsername(username), e);
        }
    }

    private String mask(String token) {
        return MaskUtils.maskToken(token);
    }
}
