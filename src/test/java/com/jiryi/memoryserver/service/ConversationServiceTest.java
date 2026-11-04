package com.jiryi.memoryserver.service;

import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.config.MemoryProperties;
import com.jiryi.memoryserver.dto.ConversationSyncDTO;
import com.jiryi.memoryserver.entity.ConversationPO;
import com.jiryi.memoryserver.mapper.ConversationMapper;
import com.jiryi.memoryserver.mapper.ConversationTombstoneMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

/**
 * 会话同步的批量与去重行为测试（不依赖数据库，纯 Mockito）
 *
 * 覆盖本次改造的关键点：
 * 1. 整批只打库一次，不再逐条往返；
 * 2. 同一请求内重复 id 必须先合并，否则批量 ON CONFLICT 会被 PG 拒绝；
 * 3. 空 upsert 不产生任何 SQL。
 */
class ConversationServiceTest {

    private ConversationMapper mapper;

    private ConversationTombstoneMapper tombstoneMapper;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(ConversationMapper.class);
        tombstoneMapper = Mockito.mock(ConversationTombstoneMapper.class);
        service = new ConversationService(mapper, tombstoneMapper, new MemoryProperties());
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertIsSentInSingleBatch() {
        ConversationSyncDTO dto = new ConversationSyncDTO();
        dto.setUpsert(List.of(item("c-1", "第一条", 1000L), item("c-2", "第二条", 2000L)));
        Mockito.when(mapper.upsertBatchIfNewer(Mockito.anyList(), Mockito.eq(7L))).thenReturn(2);

        int applied = service.sync(7L, dto).applied();

        ArgumentCaptor<List<ConversationPO>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(mapper, Mockito.times(1)).upsertBatchIfNewer(captor.capture(), Mockito.eq(7L));
        Assertions.assertEquals(2, captor.getValue().size(), "两条不同 id 应一次批量写入");
        Assertions.assertEquals(2, applied, "applied 取数据库实际影响行数");
        // 复活语义：写入前必须清掉同 id 墓碑
        Mockito.verify(tombstoneMapper).deleteByConvIds(Mockito.eq(7L), Mockito.anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateIdKeepsNewerAndCountsOnce() {
        ConversationSyncDTO dto = new ConversationSyncDTO();
        // 同一 id 提交两次，后者更新：批量语句里同 id 命中同一行两次会直接报错，必须先合并
        dto.setUpsert(List.of(item("c-1", "旧内容", 1000L), item("c-1", "新内容", 5000L)));
        Mockito.when(mapper.upsertBatchIfNewer(Mockito.anyList(), Mockito.eq(7L))).thenReturn(1);

        int applied = service.sync(7L, dto).applied();

        ArgumentCaptor<List<ConversationPO>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(mapper).upsertBatchIfNewer(captor.capture(), Mockito.eq(7L));
        List<ConversationPO> rows = captor.getValue();
        Assertions.assertEquals(1, rows.size(), "重复 id 应合并成一行");
        Assertions.assertEquals("新内容", rows.get(0).getTitle(), "同 id 取 updatedAt 较大者");
        Assertions.assertEquals(1, applied, "同一行不再被计两次");
    }

    @Test
    void emptyUpsertTouchesNoDatabase() {
        ConversationSyncDTO dto = new ConversationSyncDTO();
        dto.setUpsert(List.of());

        int applied = service.sync(7L, dto).applied();

        Mockito.verify(mapper, Mockito.never()).upsertBatchIfNewer(Mockito.anyList(), Mockito.any());
        Assertions.assertEquals(0, applied);
    }

    @Test
    @SuppressWarnings("unchecked")
    void titleFallsBackAndTruncatesByConfig() {
        MemoryProperties props = new MemoryProperties();
        props.setTitleMaxLength(8);
        ConversationService limited = new ConversationService(mapper, tombstoneMapper, props);
        ConversationSyncDTO dto = new ConversationSyncDTO();
        dto.setUpsert(List.of(item("c-1", "  ", 1000L), item("c-2", "超长的会话标题内容", 1000L)));
        Mockito.when(mapper.upsertBatchIfNewer(Mockito.anyList(), Mockito.eq(7L))).thenReturn(2);

        limited.sync(7L, dto);

        ArgumentCaptor<List<ConversationPO>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(mapper).upsertBatchIfNewer(captor.capture(), Mockito.eq(7L));
        List<ConversationPO> rows = captor.getValue();
        Assertions.assertEquals("新对话", rows.get(0).getTitle(), "空标题回落默认值");
        Assertions.assertEquals("超长的会话标题内", rows.get(1).getTitle(), "标题按配置上限截断，避免超列宽");
    }

    @Test
    @SuppressWarnings("unchecked")
    void futureTimestampIsClampedToServerTime() {
        long serverNow = System.currentTimeMillis();
        ConversationSyncDTO dto = new ConversationSyncDTO();
        // 客户端时钟超前 1 天：不钳制会让该会话永久压制其它设备的更新
        dto.setUpsert(List.of(item("c-1", "未来时间", serverNow + 24 * 3600_000L)));
        Mockito.when(mapper.upsertBatchIfNewer(Mockito.anyList(), Mockito.eq(7L))).thenReturn(1);

        ConversationService.SyncResult result = service.sync(7L, dto);

        ArgumentCaptor<List<ConversationPO>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(mapper).upsertBatchIfNewer(captor.capture(), Mockito.eq(7L));
        long stored = captor.getValue().get(0).getUpdatedAt().toInstant().toEpochMilli();
        Assertions.assertTrue(stored <= result.serverTimeMs() + 60_000L,
                "超前时间戳必须被钳制到服务端时间附近");
    }

    @Test
    void oversizedPayloadIsRejected() {
        MemoryProperties props = new MemoryProperties();
        props.setConversationPayloadMaxBytes(1024);
        ConversationService limited = new ConversationService(mapper, tombstoneMapper, props);
        ConversationSyncDTO dto = new ConversationSyncDTO();
        ConversationSyncDTO.Item item = item("c-1", "超大 payload", System.currentTimeMillis());
        item.setPayload("x".repeat(2048));
        dto.setUpsert(List.of(item));

        BizException e = Assertions.assertThrows(BizException.class, () -> limited.sync(7L, dto));
        Assertions.assertEquals(413, e.getCode(), "超限必须返回 413，而不是写入失败后 500");
        Mockito.verifyNoInteractions(mapper, tombstoneMapper);
    }

    private static ConversationSyncDTO.Item item(String id, String title, long updatedAtMs) {
        ConversationSyncDTO.Item item = new ConversationSyncDTO.Item();
        item.setId(id);
        item.setTitle(title);
        item.setPayload("{}");
        item.setUpdatedAt(updatedAtMs);
        return item;
    }
}
