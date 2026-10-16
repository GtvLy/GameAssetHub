package com.zhangchi.gameasset.common.enums;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 资产优先级（文档 3.2 {@code asset.priority} 注释：P0/P1/P2）。
 *
 * <p>P0 最高。放在枚举里而不是让前端传任意字符串，
 * 是为了让「筛选下拉的可选值」与「库里能存的值」只有一个来源。</p>
 *
 * @author 张忠驰
 */
public enum AssetPriority {

    /** 最高优先级：卡住主流程的资源 */
    P0("紧急"),

    /** 常规优先：本迭代内需要完成 */
    P1("高"),

    /** 低优先：有则更好 */
    P2("普通");

    private final String label;

    AssetPriority(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 默认优先级，与 DDL 的 DEFAULT 'P2' 保持一致 */
    public static final AssetPriority DEFAULT = P2;

    /** 字符串 → 枚举，非法返回 null */
    public static AssetPriority of(String priority) {
        if (priority == null) {
            return null;
        }
        String normalized = priority.trim().toUpperCase();
        for (AssetPriority value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    public static boolean isValid(String priority) {
        return of(priority) != null;
    }

    public static String label(String priority) {
        AssetPriority value = of(priority);
        return value == null ? String.valueOf(priority) : value.getLabel();
    }

    /** 全部合法优先级名（P0/P1/P2），用于错误提示与前端下拉 */
    public static Set<String> all() {
        Set<String> result = new LinkedHashSet<>();
        for (AssetPriority value : values()) {
            result.add(value.name());
        }
        return Collections.unmodifiableSet(result);
    }
}
