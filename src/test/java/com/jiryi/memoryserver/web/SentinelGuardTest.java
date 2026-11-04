package com.jiryi.memoryserver.web;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.common.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;

/**
 * Sentinel 埋点生命周期测试
 *
 * 关注点不是限流阈值本身，而是 Entry 的登记与收尾：
 * 1. 通过时 Entry 必须存活在请求属性上（否则并发统计与异常登记拿不到上下文）；
 * 2. 中途被限流抛 429 时，本拦截器的 afterCompletion 不会被回调，已登记的 Entry
 *    必须仍可由链上更靠前的拦截器统一 release，不能丢；
 * 3. release 之后请求属性必须清干净，且可重复调用。
 */
class SentinelGuardTest {

    /** 恒被拒绝的测试资源：count=0 时任何通过判定都不成立 */
    private static final String RES_BLOCKED = "unit-test-blocked";

    /** 不设规则的资源：任何请求都能通过 */
    private static final String RES_PASS = "unit-test-pass";

    @AfterEach
    void resetRules() {
        // 单测改动的是 Sentinel 全局规则表，必须复位，避免影响其他用例
        FlowRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    void trackKeepsEntryAliveUntilRelease() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/memory");
        try {
            SentinelGuard.track(request, RES_PASS, 1001L);
            Assertions.assertEquals(1, liveEntries(request).size(), "Entry 必须留在请求上下文里");
        } finally {
            SentinelGuard.release(request);
        }
        Assertions.assertTrue(liveEntries(request).isEmpty(), "release 后不应残留 Entry");
    }

    @Test
    void blockedTrackLeavesEarlierEntryReleasable() {
        loadBlockRule();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        // 先开一个能通过的埋点，再开一个必被拒的，等价于 RateLimitInterceptor 的连续两次 track
        SentinelGuard.track(request, RES_PASS, 1001L);

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> SentinelGuard.track(request, RES_BLOCKED, "1.1.1.1"));

        Assertions.assertEquals(ResultCode.TOO_MANY_REQUESTS, ex.getCode());
        // 抛 429 的是本拦截器，它的 afterCompletion 不会被回调；已登记的 Entry 必须还在，
        // 等链上更靠前的 ResourceCountInterceptor 在 afterCompletion 里 release
        Assertions.assertEquals(1, liveEntries(request).size(), "被拒不应丢掉已登记的 Entry");
        SentinelGuard.release(request);
        Assertions.assertTrue(liveEntries(request).isEmpty(), "release 后必须干净");
    }

    @Test
    void releaseIsIdempotent() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/me");

        SentinelGuard.release(request);
        SentinelGuard.release(request);

        Assertions.assertTrue(liveEntries(request).isEmpty());
    }

    private static void loadBlockRule() {
        FlowRule rule = new FlowRule(RES_BLOCKED);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));
    }

    /** 直接读请求属性：track/release 都以该键为准 */
    private static List<?> liveEntries(MockHttpServletRequest request) {
        Object value = request.getAttribute(SentinelGuard.ATTR_ENTRIES);
        return value instanceof List<?> list ? list : List.of();
    }
}
