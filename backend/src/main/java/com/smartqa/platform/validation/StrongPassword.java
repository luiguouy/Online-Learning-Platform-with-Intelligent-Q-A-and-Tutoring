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
import java.util.Set;

/**
 * 注册/改密口令强度校验（Issue #94 第 2 项）。
 *
 * <p><b>为什么用自定义约束而不是 {@code @Pattern}</b>：连续字符（12345678）、
 * 同字符重复（aaaaaaaa）与弱口令黑名单都属于「要算」的规则，写成正则会成为不可维护的
 * 天书，也无法给出分层的错误提示。逻辑集中在这里，注册与改密共用同一个约束，
 * 避免两条入口一套规则一个口径。</p>
 *
 * <p><b>规则与前端必须同源</b>：{@code frontend-student/src/views/auth/RegisterView.vue}
 * 的表单规则覆盖其中可静态判定的部分（长度、字母+数字、同字符、连续字符），
 * 黑名单交由服务端判定，但<b>提示文案两边一致</b>——本项目吃过「行为与提示不自洽」的亏，
 * 校验阈值改动时三处同改：本类、前端表单规则、{@code backend/README} §3.1.1。</p>
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordStrengthValidator.class)
public @interface StrongPassword {

    String MESSAGE = "密码须为 8-32 位，同时包含字母与数字，且不能使用常见弱口令或纯连续/重复字符";

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

/**
 * {@link StrongPassword} 的实现（与注解同文件、包内可见：它不是给业务代码直接调用的类型）。
 */
class PasswordStrengthValidator implements ConstraintValidator<StrongPassword, String> {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 32;

    /**
     * 常见弱口令黑名单（小写比较）。刻意只收"高频且确定"的条目：
     * 中文项目要真正做好弱口令拦截需要专业字典（如 HaveIBeenPwned 数据集），
     * 那属于新增依赖/外部服务，与本项目「零新增重型依赖」红线冲突，故不做（见 SEC-6）。
     */
    private static final Set<String> BLACKLIST = Set.of(
            "password", "password1", "passw0rd", "iloveyou", "admin123", "root1234",
            "qwerty123", "1q2w3e4r", "1qaz2wsx", "abc123456", "a1234567", "12345678",
            "123456789", "1234567890", "11111111", "00000000", "88888888");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null / 空交给 @NotBlank，避免两条约束重复报错
        if (value == null || value.isEmpty()) {
            return true;
        }
        int len = value.length();
        if (len < MIN_LENGTH || len > MAX_LENGTH) {
            return fail(context, StrongPassword.MESSAGE);
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            // 任何空白（含全角空格）一律拒：避免首尾空白型口令与“看起来是空”的密码
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return fail(context, StrongPassword.MESSAGE);
            }
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            return fail(context, StrongPassword.MESSAGE);
        }

        if (isAllSameChar(value) || isSequential(value) || BLACKLIST.contains(value.toLowerCase())) {
            return fail(context, StrongPassword.MESSAGE);
        }
        return true;
    }

    /** 全部同一字符，如 aaaaaaaa / 11111111（后者也在黑名单，这里做结构性兜底） */
    private boolean isAllSameChar(String value) {
        return value.chars().distinct().count() == 1;
    }

    /**
     * 整串等步长递增/递减，如 12345678、abcdefgh、87654321。
     *
     * <p>只看"整串同一差值"，不做子串检测：子串检测会把 "A1b2C3d4" 这类正常口令
     * 与真弱口令混为一谈，误伤的代价大于收益。</p>
     */
    private boolean isSequential(String value) {
        int step = value.charAt(1) - value.charAt(0);
        if (step == 0) {
            return false; // 同字符已由 isAllSameChar 覆盖
        }
        for (int i = 2; i < value.length(); i++) {
            if (value.charAt(i) - value.charAt(i - 1) != step) {
                return false;
            }
        }
        return Math.abs(step) == 1;
    }

    private boolean fail(ConstraintValidatorContext context, String message) {
        // 不禁用默认违规会出现两条同义 violation（默认 + 模板），虽然全局处理只取第一条，
        // 但重复违规会让后续改成“拼接所有提示”时直接重复文案
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
