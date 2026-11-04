package com.jiryi.memoryserver.task;

import com.jiryi.memoryserver.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 会话流水清理
 *
 * Redis 侧靠 TTL 自动回收，PG 的 t_session 只增不减，必须定期清，
 * 否则一年就是千万级死行，鉴权回落查询会越来越慢。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupTask {

    /** 单批删除行数：够大保证吞吐，够小保证事务短 */
    private static final int BATCH_SIZE = 2000;

    /** 单次任务最多跑多少批，防御性上限（100 万行），超出部分留给下一周期 */
    private static final int MAX_BATCHES = 500;

    private final SessionMapper sessionMapper;

    /**
     * 每天 03:30 分批清理已过期流水；此时段请求量最低，删除不与业务争抢锁
     * 依赖 idx_session_expires_at 取批，不做全表扫描，也不一个语句删完
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void cleanExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        long total = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                int rows = sessionMapper.deleteExpiredBatch(now, BATCH_SIZE);
                total += rows;
                // 未满一批说明死行已清完，不必再打库
                if (rows < BATCH_SIZE) {
                    break;
                }
            }
            if (total > 0) {
                log.info("过期会话流水已清理 rows={}", total);
            }
        } catch (Exception e) {
            // 清理失败不能影响主流程，只告警；下一周期从剩余死行接着清
            log.error("过期会话清理失败，将于下一周期重试，本次已清理 rows={}", total, e);
        }
    }
}
