package com.jiryi.memoryserver.controller;

import com.jiryi.memoryserver.common.Result;
import com.jiryi.memoryserver.common.UserContext;
import com.jiryi.memoryserver.dto.ConversationSyncDTO;
import com.jiryi.memoryserver.service.ConversationService;
import com.jiryi.memoryserver.vo.ConversationListVO;
import com.jiryi.memoryserver.vo.ConversationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话同步接口：GET 分页/增量拉取，PUT 增量同步（remove + upsert）
 *
 * 资源级限流统一由 ResourceCountInterceptor 按 URI 埋点，
 * 此处不再叠加 @SentinelResource，避免同一请求被统计两次。
 */
@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 拉取会话列表
     *
     * @param limit  单页条数，缺省 200，超过配置上限自动收敛
     * @param offset 偏移量，缺省 0
     * @param since  epoch 毫秒，传入后只返回该时间之后的更新与删除（增量同步）
     */
    @GetMapping
    public Result<ConversationListVO> list(@RequestParam(name = "limit", required = false) Integer limit,
                                           @RequestParam(name = "offset", required = false) Integer offset,
                                           @RequestParam(name = "since", required = false) Long since) {
        return Result.ok(conversationService.list(UserContext.requireUserId(),
                limit == null ? 0 : limit,
                offset == null ? 0 : offset,
                since));
    }

    /** 单条会话：越权与不存在统一 404 */
    @GetMapping("/{id}")
    public Result<ConversationVO> get(@PathVariable(name = "id") String id) {
        return Result.ok(conversationService.get(UserContext.requireUserId(), id));
    }

    /**
     * 删除单条会话：本地删除时立即调用，服务端逻辑删除并登记墓碑，
     * 其它端下次拉取会收到 deleted 墓碑并删掉本地副本。
     * 幂等：会话不存在也返回成功。
     */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> remove(@PathVariable(name = "id") String id) {
        conversationService.removeOne(UserContext.requireUserId(), id);
        return Result.ok(Map.of("ok", true));
    }

    @PutMapping
    public Result<Map<String, Object>> sync(@Valid @RequestBody ConversationSyncDTO dto) {
        ConversationService.SyncResult result = conversationService.sync(UserContext.requireUserId(), dto);
        return Result.ok(Map.of(
                "ok", true,
                "applied", result.applied(),
                "removed", result.removed(),
                // 服务端时间作为前端下次增量拉取的游标，避免依赖客户端时钟
                "serverTimeMs", result.serverTimeMs()));
    }
}
