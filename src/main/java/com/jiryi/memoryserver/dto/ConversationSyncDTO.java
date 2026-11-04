package com.jiryi.memoryserver.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话同步入参：remove 先删，upsert 逐条按 updatedAt 较新者胜
 * 时间戳沿用客户端的 epoch 毫秒，便于跨端时钟对齐后直接比较
 */
@Data
public class ConversationSyncDTO {

    /** 待删除的会话 ID 列表 */
    @Size(max = 1000, message = "单次删除会话不能超过 1000 条")
    private List<@NotBlank(message = "会话ID不能为空") String> remove = new ArrayList<>();

    @Valid
    @Size(max = 500, message = "单次同步会话不能超过 500 条")
    private List<Item> upsert = new ArrayList<>();

    @Data
    public static class Item {

        @NotBlank(message = "会话ID不能为空")
        @Size(max = 64, message = "会话ID长度不能超过 64")
        private String id;

        @Size(max = 200, message = "会话标题原始长度不能超过 200")
        private String title;

        /** 会话完整 JSON 字符串，服务端不解析 */
        @NotNull(message = "payload 不能为 null")
        private String payload;

        /** 创建时间 epoch 毫秒，缺省取 updatedAt */
        private Long createdAt;

        /** 更新时间 epoch 毫秒，冲突判定依据 */
        @NotNull(message = "updatedAt 不能为 null")
        private Long updatedAt;
    }
}
