package com.jiryi.memoryserver.common;

import java.io.Serializable;

/**
 * 统一响应封装：所有接口出参一致，前端只需判 code
 *
 * @param <T> 业务数据类型
 */
public class Result<T> implements Serializable {

    /** 成功码 */
    public static final int OK = 0;

    private int code;
    private String msg;
    private T data;

    public Result() {
    }

    public Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(OK, "success", data);
    }

    /** 无数据的成功响应，data 不序列化（jackson non_null） */
    public static Result<Void> ok() {
        return new Result<>(OK, "success", null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
