package com.jiryi.memoryserver.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 规则装载
 *
 * 只在 rule-source=local 时装载内置规则；rule-source=nacos 时交由
 * Spring Cloud Alibaba 的 datasource（spring.cloud.sentinel.datasource.*）托管，
 * 两处同时生效会导致规则被互相覆盖，表现为"改了 Nacos 没效果"。
 *
 * 埋点方式：本项目不使用 webmvc 自动适配，而是拦截器手动 SphU.entry，
 * 目的是拿到 userId/IP 这些框架看不见的维度，同时避免同一请求被重复统计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SentinelInitConfig implements ApplicationRunner {

    private static final String FLOW_RULES = "sentinel/flow-rules.json";

    private static final String PARAM_RULES = "sentinel/param-rules.json";

    private final MemoryProperties props;

    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (props.getSentinel().getRuleSource() == MemoryProperties.RuleSource.NACOS) {
            log.info("Sentinel 规则来源为 Nacos，跳过内置规则装载");
            return;
        }
        List<FlowRule> flowRules = loadFlowRules();
        List<ParamFlowRule> paramRules = loadParamRules();
        // 空列表也要注册：否则历史规则残留（单测/热更新场景）
        FlowRuleManager.loadRules(flowRules);
        ParamFlowRuleManager.loadRules(paramRules);
        log.info("Sentinel 内置规则装载完成 flow={}, param={}", flowRules.size(), paramRules.size());
    }

    private List<FlowRule> loadFlowRules() {
        List<FlowRule> rules = read(FLOW_RULES, new TypeReference<List<FlowRule>>() {
        });
        for (FlowRule rule : rules) {
            // JSON 里省略的字段补默认值，避免 count=0 直接把接口限死
            if (rule.getGrade() == 0) {
                rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            }
            if (rule.getLimitApp() == null) {
                rule.setLimitApp("default");
            }
        }
        return rules;
    }

    private List<ParamFlowRule> loadParamRules() {
        List<ParamFlowRule> rules = read(PARAM_RULES, new TypeReference<List<ParamFlowRule>>() {
        });
        for (ParamFlowRule rule : rules) {
            if (rule.getGrade() == 0) {
                rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            }
            if (rule.getDurationInSec() <= 0) {
                rule.setDurationInSec(1L);
            }
            if (rule.getParamFlowItemList() == null) {
                rule.setParamFlowItemList(new ArrayList<>());
            }
        }
        return rules;
    }

    /** 规则文件缺失或格式错误只告警不限流，绝不能让规则问题拖垮启动 */
    private <T> List<T> read(String location, TypeReference<List<T>> type) {
        Resource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            log.warn("Sentinel 规则文件不存在: {}", location);
            return new ArrayList<>();
        }
        try (InputStream in = resource.getInputStream()) {
            List<T> list = objectMapper.readValue(in, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.error("Sentinel 规则解析失败，该维度不限流: {}", location, e);
            return new ArrayList<>();
        }
    }
}
