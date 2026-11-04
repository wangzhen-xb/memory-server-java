package com.jiryi.memoryserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话列表响应：分页数据 + 删除墓碑 + 分页元信息
 *
 * deleted 与 items 并列返回，客户端才能在一次请求里同时完成"新增/更新"与"删除"的合并，
 * 不必再为删除单独轮询一个接口。
 */
@Data
@AllArgsConstructor
public class ConversationListVO {

    /** 当前页会话，按 updatedAt 倒序 */
    private List<ConversationVO> items;

    /** 删除墓碑（按删除时间升序） */
    private List<Deleted> deleted;

    /** 当前用户会话总数；增量模式（带 since）下等于 items.size()，不做额外 count */
    private long total;

    /** 是否还有下一页 / 下一批增量 */
    private boolean hasMore;

    @Data
    @AllArgsConstructor
    public static class Deleted {

        private String id;

        private OffsetDateTime deletedAt;

        /** epoch 毫秒，供客户端直接比较，避免二次解析 */
        private Long deletedAtMs;
    }
}
