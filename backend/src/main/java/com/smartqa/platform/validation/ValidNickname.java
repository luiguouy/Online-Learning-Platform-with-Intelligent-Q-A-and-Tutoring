package com.smartqa.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 展示名字段（昵称/姓名）的入库校验（Issue #94 第 4 项）。
 *
 * <p><b>为什么注册与改资料必须共用同一个约束</b>：两处写的是同一列
 * {@code sys_user.nickname}，此前一个限 {@code ≤50}（注册）、一个限 {@code 1-30}（改资料），
 * 于是"用 40 字姓名注册成功后，再改资料就保存不了"这种割裂行为真实存在。
 * 统一取<b>严</b>的一侧（1-30），DB 的 50 留作余量，不再各写一套。</p>
 *
 * <p><b>本约束只管"结构卫生"，刻意不做敏感词过滤</b>：中文敏感词需要专业词库或外部服务，
 * 与本项目「零新增重型依赖」红线冲突，且判罚口径属于产品决策。前端已有 XSS 三道防线
 * （markdown-it {@code html:false} + DOMPurify 白名单 + Vue 插值转义），
 * 因此这里拦的是会污染<b>日志与教师端导出</b>的字符，而不是内容合规。
 * 该取舍记录在 {@code dev-docs/SECURITY_DECISIONS.md} 的 SEC-6，勿当作漏做。</p>
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NicknameValidator.class)
public @interface ValidNickname {

    String MESSAGE = "名称须为 1-30 个字符，且不含控制字符、零宽字符或尖括号";

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

/**
 * {@link ValidNickname} 的实现（与注解同文件、包内可见）。
 */
class NicknameValidator implements ConstraintValidator<ValidNickname, String> {

    private static final int MIN_LENGTH = 1;
    private static final int MAX_LENGTH = 30;

    /** 灌水判定：整串同一字符达到该数量即拒绝（如 aaaaa / 666666） */
    private static final int MAX_SAME_CHAR_RUN = 4;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 空白交给 @NotBlank 判定，避免重复报同一条
        if (value == null) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            return fail(context);
        }
        // 首尾空白会被 trim 掉：说明客户端传的就是带空白的值，直接拒绝而不是静默改写，
        // 否则会出现"我存的名字和你给我看的名字不是同一个字符串"
        if (!trimmed.equals(value)) {
            return fail(context);
        }

        int sameRun = 1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || isZeroWidth(c) || c == '<' || c == '>') {
                return fail(context);
            }
            if (i > 0 && c == value.charAt(i - 1)) {
                if (++sameRun > MAX_SAME_CHAR_RUN) {
                    return fail(context);
                }
            } else {
                sameRun = 1;
            }
        }
        return true;
    }

    /**
     * 零宽与双向控制字符：肉眼不可见，却能让同一个显示名在界面上看起来不同，
     * 教师端审计与日志里会直接变成对不齐的"同名"记录。
     */
    private boolean isZeroWidth(char c) {
        return c == '\u200B' || c == '\u200C' || c == '\u200D'
                || c == '\u200E' || c == '\u200F' || c == '\uFEFF';
    }

    private boolean fail(ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(ValidNickname.MESSAGE).addConstraintViolation();
        return false;
    }
}
