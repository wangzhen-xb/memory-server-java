package com.jiryi.memoryserver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jiryi.memoryserver.config.MemoryProperties;
import com.jiryi.memoryserver.dto.MemoryReplaceDTO;
import com.jiryi.memoryserver.entity.MemoryPO;
import com.jiryi.memoryserver.mapper.MemoryMapper;
import com.jiryi.memoryserver.vo.MemoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆：全量替换语义（PUT 传入完整列表，服务端整体重建）
 * 与原 Node 实现保持一致的清洗规则：折叠空白 -> 去空 -> 截断 -> 限条数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final MemoryMapper memoryMapper;

    private final MemoryProperties props;

    /** 记忆列表，按创建时间正序 */
    public List<MemoryVO> list(Long userId) {
        List<MemoryPO> rows = memoryMapper.selectList(Wrappers.<MemoryPO>lambdaQuery()
                .select(MemoryPO::getId, MemoryPO::getContent, MemoryPO::getCreatedAt)
                .eq(MemoryPO::getUserId, userId)
                .orderByAsc(MemoryPO::getCreatedAt)
                .orderByAsc(MemoryPO::getId));
        return rows.stream().map(MemoryVO::from).toList();
    }

    /**
     * 全量替换：同一事务内清空再批量写回，避免中途失败导致记忆丢失
     *
     * @return 替换后的完整列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MemoryVO> replace(Long userId, MemoryReplaceDTO dto) {
        List<String> clean = sanitize(dto.getItems() == null ? List.of() : dto.getItems());
        memoryMapper.deleteByUserId(userId);
        if (!clean.isEmpty()) {
            List<MemoryPO> batch = new ArrayList<>(clean.size());
            for (String content : clean) {
                MemoryPO po = new MemoryPO();
                po.setUserId(userId);
                po.setContent(content);
                batch.add(po);
            }
            // 批量插入，不在循环里逐条打库
            memoryMapper.insertBatch(batch);
        }
        log.info("记忆全量替换 userId={}, count={}", userId, clean.size());
        return list(userId);
    }

    /** 清空该用户全部记忆（与替换同一语义，物理删除不留死行） */
    public void clear(Long userId) {
        memoryMapper.deleteByUserId(userId);
        log.info("记忆已清空 userId={}", userId);
    }

    /**
     * 清洗规则：连续空白折叠为单空格、去空项、按配置截断长度与条数
     */
    private List<String> sanitize(List<String> raw) {
        List<String> out = new ArrayList<>();
        int maxLen = props.getMemoryMaxLength();
        int maxCount = props.getMemoryMaxCount();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String value = item.replaceAll("\\s+", " ").trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > maxLen) {
                value = value.substring(0, maxLen);
            }
            out.add(value);
            if (out.size() >= maxCount) {
                // 超出上限直接丢弃尾部，与原实现 slice 语义一致
                break;
            }
        }
        return out;
    }
}
