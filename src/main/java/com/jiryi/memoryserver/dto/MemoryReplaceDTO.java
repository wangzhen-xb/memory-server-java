package com.jiryi.memoryserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆全量替换入参：items 为空数组表示清空
 * 条数与单条长度的业务上限在 Service 层按配置裁剪（阈值可经 Nacos 动态调整）
 */
@Data
public class MemoryReplaceDTO {

    /** 静态兜底上限 200 条，超出直接 400；业务实际保留条数由 memory.memory-max-count 决定 */
    @Size(max = 200, message = "单次提交记忆不能超过 200 条")
    private List<@NotBlank(message = "记忆内容不能为空") @Size(max = 500, message = "单条记忆原始长度不能超过 500") String> items = new ArrayList<>();
}
