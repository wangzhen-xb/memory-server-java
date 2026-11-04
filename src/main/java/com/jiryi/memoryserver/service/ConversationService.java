package com.jiryi.memoryserver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.common.ResultCode;
import com.jiryi.memoryserver.config.MemoryProperties;
import com.jiryi.memoryserver.dto.ConversationSyncDTO;
import com.jiryi.memoryserver.entity.ConversationPO;
import com.jiryi.memoryserver.mapper.ConversationMapper;
import com.jiryi.memoryserver.mapper.ConversationTombstoneMapper;
import com.jiryi.memoryserver.vo.ConversationListVO;
import com.jiryi.memoryserver.vo.ConversationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话同步：remove 先删，upsert 批量"较新者胜"
 *
 * 冲突判定放在 SQL 的 ON CONFLICT ... WHERE 里完成，比"先查再写"少一次往返且无竞态；
 * 整批走一条多值 INSERT，避免 500 条就是 500 次打库、且长事务持锁；
 * 客户端时间戳只做上限钳制（未来时间不能永久压制其它设备），不做回拨。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final String DEFAULT_TITLE = "新对话";

    /** 允许的客户端时钟超前量：超过该值即视为异常时钟，按服务端时间落库 */
    private static final long CLOCK_SKEW_MS = 5 * 60 * 1000L;

    private final ConversationMapper conversationMapper;

    private final ConversationTombstoneMapper tombstoneMapper;

    private final MemoryProperties props;

    /**
     * 会话列表：支持分页与 since 增量
     *
     * @param limit   单页条数，收敛到 1..listPageMax
     * @param offset  偏移量，负数按 0 处理
     * @param sinceMs epoch 毫秒；非空时只返回此后更新/删除的记录，用于增量同步
     */
    public ConversationListVO list(Long userId, int limit, int offset, Long sinceMs) {
        int size = clampLimit(limit);
        int skip = Math.max(offset, 0);
        OffsetDateTime since = sinceMs == null || sinceMs <= 0 ? null : toOffset(sinceMs);

        var query = Wrappers.<ConversationPO>lambdaQuery()
                .select(ConversationPO::getId, ConversationPO::getTitle, ConversationPO::getPayload,
                        ConversationPO::getCreatedAt, ConversationPO::getUpdatedAt)
                .eq(ConversationPO::getUserId, userId)
                .orderByDesc(ConversationPO::getUpdatedAt);
        if (since != null) {
            query.gt(ConversationPO::getUpdatedAt, since);
        }
        // PaginationInnerInterceptor 只支持页号，无法表达任意 offset；
        // 前端按 limit 递增 offset，这里用 LIMIT/OFFSET 保证语义精确，且始终带上限
        query.last("LIMIT " + size + " OFFSET " + skip);
        List<ConversationVO> items = conversationMapper.selectList(query).stream()
                .map(ConversationVO::from).toList();

        long total;
        if (since == null) {
            total = conversationMapper.selectCount(Wrappers.<ConversationPO>lambdaQuery()
                    .eq(ConversationPO::getUserId, userId));
        } else {
            // 增量模式不统计总数，少一次全表 count
            total = items.size();
        }
        boolean hasMore = since == null
                ? (long) skip + items.size() < total
                : items.size() >= size;

        return new ConversationListVO(items, listDeleted(userId, since, props.getListPageMax()), total, hasMore);
    }

    /** 单条会话查询：归属由 user_id 条件保证，非本人与不存在统一返回 404 */
    public ConversationVO get(Long userId, String id) {
        if (id == null || id.isBlank()) {
            throw BizException.notFound("会话不存在");
        }
        ConversationPO po = conversationMapper.selectOne(Wrappers.<ConversationPO>lambdaQuery()
                .select(ConversationPO::getId, ConversationPO::getTitle, ConversationPO::getPayload,
                        ConversationPO::getCreatedAt, ConversationPO::getUpdatedAt)
                .eq(ConversationPO::getId, id.trim())
                .eq(ConversationPO::getUserId, userId));
        if (po == null) {
            throw BizException.notFound("会话不存在");
        }
        return ConversationVO.from(po);
    }

    /**
     * 同步会话
     *
     * @return 实际生效条数、删除条数、服务端时间（作为下次 since 游标）
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult sync(Long userId, ConversationSyncDTO dto) {
        long serverNow = System.currentTimeMillis();
        int removed = remove(userId, dto.getRemove(), serverNow);
        List<ConversationPO> rows = toBatch(dto.getUpsert(), serverNow);
        if (!rows.isEmpty()) {
            // 会话被重新写入即视为复活，清掉墓碑，避免其它端拉取后继续删除
            tombstoneMapper.deleteByConvIds(userId, rows.stream().map(ConversationPO::getId).toList());
        }
        int applied = rows.isEmpty() ? 0 : conversationMapper.upsertBatchIfNewer(rows, userId);
        log.info("会话同步 userId={}, removed={}, applied={}, skipped={}",
                userId, removed, applied, rows.size() - applied);
        return new SyncResult(applied, removed, serverNow);
    }

    /** 同步结果，交给 Controller 原样封装为 data */
    public record SyncResult(int applied, int removed, long serverTimeMs) {
    }

    /**
     * 删除单条会话：客户端删除时立即调用的显式接口。
     * 与批量同步中的 remove 语义完全一致（逻辑删除 + 写墓碑），保证"本地删了云端也删"：
     * 即使本地随后重启或换端，墓碑也能让其它端下次拉取时同步删掉。
     * 幂等：会话不存在或已删除同样返回成功，客户端无需区分。
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeOne(Long userId, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        int affected = remove(userId, List.of(id.trim()), now);
        log.info("会话删除 userId={}, id={}, affected={}", userId, id, affected);
    }

    /** 墓碑按删除时间升序返回，since 为空时取全量（前端首屏用它清理本地残留） */
    private List<ConversationListVO.Deleted> listDeleted(Long userId, OffsetDateTime since, int limit) {
        OffsetDateTime from = since == null ? toOffset(0L) : since;
        return tombstoneMapper.selectSince(userId, from, limit).stream()
                .map(po -> new ConversationListVO.Deleted(po.getConvId(), po.getDeletedAt(),
                        po.getDeletedAt() == null ? 0L : po.getDeletedAt().toInstant().toEpochMilli()))
                .toList();
    }

    /**
     * 清洗并去重：同一请求内重复 id 先按 updatedAt 取最新再入库。
     * 批量 ON CONFLICT DO UPDATE 命中同一行两次会被 PG 直接拒绝
     * （cannot affect row a second time），去重既是优化也是必要条件。
     * 时间戳相等时保留先出现的一条，与原逐条写入的净结果一致。
     */
    private List<ConversationPO> toBatch(List<ConversationSyncDTO.Item> items, long serverNow) {
        List<ConversationSyncDTO.Item> list = safe(items);
        Map<String, ConversationPO> byId = new LinkedHashMap<>(Math.max(16, list.size() * 2));
        for (ConversationSyncDTO.Item item : list) {
            ConversationPO po = toPO(item, serverNow);
            byId.merge(po.getId(), po, ConversationService::newer);
        }
        return new ArrayList<>(byId.values());
    }

    /** 两条同 id 记录取 updatedAt 较大者，相等保留已入 Map 的那条 */
    private static ConversationPO newer(ConversationPO held, ConversationPO incoming) {
        return incoming.getUpdatedAt().isAfter(held.getUpdatedAt()) ? incoming : held;
    }

    /**
     * 删除会话：逻辑删除 + 写墓碑
     *
     * 墓碑对"服务端已不存在"的 id 同样写入：其它设备可能还留着该会话，
     * 只有墓碑能让它们下次拉取时把本地副本删掉。
     */
    private int remove(Long userId, List<String> ids, long serverNowMs) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> valid = ids.stream()
                .filter(x -> x != null && !x.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (valid.isEmpty()) {
            return 0;
        }
        // in 条件带上 userId，跨用户删除直接不命中，防越权
        int deleted = conversationMapper.delete(Wrappers.<ConversationPO>lambdaQuery()
                .eq(ConversationPO::getUserId, userId)
                .in(ConversationPO::getId, valid));
        tombstoneMapper.upsertBatch(valid, userId, toOffset(serverNowMs));
        return deleted;
    }

    private ConversationPO toPO(ConversationSyncDTO.Item item, long serverNow) {
        // @NotNull 已在入口拦空，此处再兼容一道防止直接内部调用时的 NPE
        long updatedAtMs = item.getUpdatedAt() == null ? serverNow : item.getUpdatedAt();
        // 未来时间戳钳制：否则一台时钟错误的设备会把其它设备的所有更新永久压制
        if (updatedAtMs > serverNow + CLOCK_SKEW_MS) {
            updatedAtMs = serverNow;
        }
        long createdAtMs = item.getCreatedAt() == null ? updatedAtMs : item.getCreatedAt();
        if (createdAtMs > updatedAtMs) {
            createdAtMs = updatedAtMs;
        }
        String title = item.getTitle() == null ? "" : item.getTitle().trim();
        if (title.isEmpty()) {
            title = DEFAULT_TITLE;
        }
        if (title.length() > props.getTitleMaxLength()) {
            title = title.substring(0, props.getTitleMaxLength());
        }
        String payload = item.getPayload() == null ? "" : item.getPayload();
        checkPayloadSize(payload);
        ConversationPO po = new ConversationPO();
        po.setId(item.getId().trim());
        po.setTitle(title);
        po.setPayload(payload);
        po.setCreatedAt(toOffset(createdAtMs));
        po.setUpdatedAt(toOffset(updatedAtMs));
        return po;
    }

    /**
     * 单条 payload 字节校验：条数上限约束不了单条长度，必须按真实 UTF-8 字节兜住
     *
     * 先用字符数快速判死（字符数 ≤ 字节数，超了必然超），再按实际字节精确判定，
     * 避免对每个正常大小的 payload 都做一次全量编码。
     */
    private void checkPayloadSize(String payload) {
        long maxBytes = props.getConversationPayloadMaxBytes();
        if (payload.length() > maxBytes) {
            throw tooLarge(maxBytes);
        }
        if (payload.length() * 4L < maxBytes) {
            // 单字符最多 4 字节，字符数 *4 仍小于上限则必然不超，跳过编码
            return;
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw tooLarge(maxBytes);
        }
    }

    private static BizException tooLarge(long maxBytes) {
        return new BizException(ResultCode.PAYLOAD_TOO_LARGE,
                "单条会话超过上限 " + (maxBytes / (1024 * 1024)) + "MB，请清理历史附件后重试");
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return Math.min(200, props.getListPageMax());
        }
        return Math.min(limit, props.getListPageMax());
    }

    private static OffsetDateTime toOffset(long epochMilli) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC);
    }

    private static List<ConversationSyncDTO.Item> safe(List<ConversationSyncDTO.Item> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
