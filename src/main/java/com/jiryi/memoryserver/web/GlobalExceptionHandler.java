package com.jiryi.memoryserver.web;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.jiryi.memoryserver.common.BizException;
import com.jiryi.memoryserver.common.Result;
import com.jiryi.memoryserver.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：任何异常都转统一 Result，绝不把堆栈或 SQL 片段透给前端
 *
 * HTTP 状态码与业务 code 同步返回，方便前端拦截器按 status 处理，也方便网关统计。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按 code 映射状态码 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.info("业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(toStatus(e.getCode())).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** @Valid 失败：只回第一条，避免把整个 DTO 结构暴露出去 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String msg = first == null ? "请求参数不合法" : first.getDefaultMessage();
        return badRequest(msg);
    }

    /** 方法级约束（含集合元素上的 @Size/@NotBlank） */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleMethodValid(HandlerMethodValidationException e) {
        return badRequest("请求参数不合法");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return badRequest(msg.isEmpty() ? "请求参数不合法" : msg);
    }

    /**
     * JSON 解析失败：区分"体过大"与"格式错误"，前者是 413
     *
     * 判据取 BodyLimitFilter 打下的请求属性：Jackson 会把读流抛出的 IOException
     * 逐层包装（JsonIOException / HttpMessageNotReadableException），靠异常类型或
     * 消息文本匹配很脆弱，属性标记不受包装层数影响。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e,
                                                         HttpServletRequest request) {
        ResponseEntity<Result<Void>> tooLarge = tooLarge(request);
        if (tooLarge != null) {
            return tooLarge;
        }
        log.info("请求体解析失败: {}", e.getMessage());
        return badRequest("请求体不是合法 JSON");
    }

    /**
     * Sentinel 限流：注解切面或其它异步入口抛出的 BlockException 兜底转 429
     * 主链路的限流由 SentinelGuard 主动抛 BizException(429)，此处只做最后一道防线。
     */
    @ExceptionHandler(BlockException.class)
    public ResponseEntity<Result<Void>> handleBlock(BlockException e) {
        log.warn("触发限流 resource={}", e.getRule() == null ? "" : e.getRule().getResource());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Result.fail(ResultCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"));
    }

    /** 路径不存在：保持原 Node 版 404 语义 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ResultCode.NOT_FOUND, "接口不存在"));
    }

    /**
     * 兜底：登记到 Sentinel 异常统计（供熔断规则使用），日志只打摘要
     * 返回统一话术，绝不把异常类名/SQL 透出去
     *
     * 超限请求先短路成 413：它是客户端错误，不能走下面的 500 分支，
     * 否则一个刷超大包的攻击就会把异常比例推高、误触熔断。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e, HttpServletRequest request) {
        ResponseEntity<Result<Void>> tooLarge = tooLarge(request);
        if (tooLarge != null) {
            return tooLarge;
        }
        SentinelGuard.traceIfServerError(e);
        log.error("未预期异常 type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.SERVER_ERROR, "服务器内部错误"));
    }

    /** 请求体超硬上限时返回 413 响应，未超限返回 null */
    private static ResponseEntity<Result<Void>> tooLarge(HttpServletRequest request) {
        if (request.getAttribute(BodyLimitFilter.ATTR_TOO_LARGE) instanceof Long limit) {
            log.info("请求体超限 path={}, limit={}B", request.getServletPath(), limit);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Result.fail(ResultCode.PAYLOAD_TOO_LARGE,
                            "请求体超过上限 " + (limit / 1024) + "KB"));
        }
        return null;
    }

    private static ResponseEntity<Result<Void>> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, msg));
    }

    private static HttpStatus toStatus(int code) {
        HttpStatus found = HttpStatus.resolve(code);
        return found == null ? HttpStatus.BAD_REQUEST : found;
    }
}
