package com.smartqa.platform.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应封装。所有 REST 接口必须返回它，严禁裸返回任何其他结构。
 *
 * <p>code 约定（DEV_SPECIFICATION 第四章）：</p>
 * <ul>
 *   <li>200 — 成功</li>
 *   <li>400 — 业务警告 / 参数错误</li>
 *   <li>401 — 未登录</li>
 *   <li>403 — 无权</li>
 *   <li>404 — 资源不存在（本模块用于课件 / 课程 / 记录不存在）</li>
 *   <li>429 — 触发限流</li>
 *   <li>500 — 系统异常</li>
 * </ul>
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;

    private String message;

    private T data;

    private Long timestamp;

    public static <T> Result<T> success(T data) {
        return Result.<T>builder()
                .code(200)
                .message("操作成功")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return Result.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
