package com.jiryi.memoryserver.web;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 手动埋点：注解只能按资源名限流，做不了"按用户/按 IP"的维度，
 * 因此热点参数必须在拦截器里用 SphU.entry(name, IN, 1, arg) 显式传入
 *
 * Entry 生命周期：打开后挂在请求属性上，由 ResourceCountInterceptor.afterCompletion
 * 统一按后进先出关闭。不能"打开即关闭"——那样并发线程数恒为 0，
 * Tracer.trace 也取不到上下文条目，异常比例/熔断规则形同虚设。
 * QPS 规则（grade=1）在 entry 打开时就完成统计，改造后行为不变。
 */
@Slf4j
public final class SentinelGuard {

    /** 请求属性键：本请求已打开的 Entry 列表，注册顺序即打开顺序（同包单测直接引用） */
    static final String ATTR_ENTRIES = "memory.sentinelEntries";

    /** 按用户维度 */
    public static final String RES_USER = "userId:api";

    public static final String RES_USER_MEMORY = "userId:memory-replace";

    public static final String RES_USER_CONVERSATION = "userId:conversation-sync";

    /** 按 IP 维度（未鉴权的登录/注册只能按 IP） */
    public static final String RES_IP_AUTH = "ip:auth";

    private SentinelGuard() {
    }

    /**
     * 带热点参数的埋点，通过则登记到请求属性，触发限流直接抛 429
     *
     * @param resource 资源名，与规则中的 resource 对应
     * @param arg      热点参数（userId 或 ip）
     * @throws BizException 触发限流时抛 429
     */
    public static void track(HttpServletRequest request, String resource, Object arg) {
        List<Entry> entries = entriesOf(request);
        try {
            entries.add(SphU.entry(resource, EntryType.IN, 1, arg));
        } catch (BlockException e) {
            // 被拒时 SphU.entry 不会返回 Entry，本次没有新增要回收的东西；
            // 先前已登记的 Entry 由链上更靠前的 ResourceCountInterceptor 统一 release
            log.warn("触发限流 resource={}, arg={}, rule={}", resource, arg,
                    e.getRule() == null ? "" : e.getRule().getLimitApp());
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
    }

    /**
     * 关闭本请求全部埋点：只由链上最先生效的拦截器在 afterCompletion 调用一次
     * 调用点必须晚于 GlobalExceptionHandler 的异常登记，否则 Tracer 统计不到
     */
    public static void release(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR_ENTRIES);
        request.removeAttribute(ATTR_ENTRIES);
        if (!(value instanceof List<?> entries)) {
            return;
        }
        // 列表已从请求上摘除，只需按后进先出逐个关闭，与打开顺序对称，
        // 否则 Sentinel 上下文链会错乱
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!(entries.get(i) instanceof Entry entry)) {
                continue;
            }
            try {
                entry.close();
            } catch (Exception e) {
                // 清理动作绝不能影响响应，只告警
                log.warn("Sentinel 埋点关闭异常", e);
            }
        }
    }

    /** 业务异常登记：让 Sentinel 的降级/系统保护规则能感知异常比例
     * 注意 4xx 属于客户端错误，不应计入异常比例，否则会因脏请求触发熔断 */
    public static void traceIfServerError(Throwable e) {
        if (e instanceof BizException biz && biz.getCode() < ResultCode.SERVER_ERROR) {
            return;
        }
        Tracer.trace(e);
    }

    /** 取请求级 Entry 列表，首次调用时建立 */
    private static List<Entry> entriesOf(HttpServletRequest request) {
        Object value = request.getAttribute(ATTR_ENTRIES);
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Entry> typed = (List<Entry>) list;
            return typed;
        }
        List<Entry> created = new ArrayList<>(3);
        request.setAttribute(ATTR_ENTRIES, created);
        return created;
    }
}
