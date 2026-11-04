package com.jiryi.memoryserver.controller;

import com.jiryi.memoryserver.common.Result;
import com.jiryi.memoryserver.common.UserContext;
import com.jiryi.memoryserver.dto.MemoryReplaceDTO;
import com.jiryi.memoryserver.service.MemoryService;
import com.jiryi.memoryserver.vo.MemoryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆接口
 * 注意：userId 一律取自登录上下文，绝不接受客户端入参，杜绝越权读写他人记忆
 */
@RestController
@RequestMapping("/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    @GetMapping
    public Result<Map<String, List<MemoryVO>>> list() {
        return Result.ok(Map.of("items", memoryService.list(UserContext.requireUserId())));
    }

    /** 全量替换：入参是完整列表，不是增量 */
    @PutMapping
    public Result<Map<String, List<MemoryVO>>> replace(@Valid @RequestBody MemoryReplaceDTO dto) {
        return Result.ok(Map.of("items", memoryService.replace(UserContext.requireUserId(), dto)));
    }

    @DeleteMapping
    public Result<Map<String, Object>> clear() {
        memoryService.clear(UserContext.requireUserId());
        return Result.ok(Map.of("ok", true));
    }
}
