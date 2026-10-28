package com.demo.gameasset.common.enums;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 任务优先级（文档 3.2 {@code task.priority} 注释：P0/P1/P2）。
 *
 * <p><b>为什么不直接复用 {@link AssetPriority}</b>：两者目前取值相同，
 * 但它们是两个业务域的独立概念。资产优先级表达「这个资源对项目有多关键」，
 * 任务优先级表达「这件事多急」。共用枚举意味着哪天美术管线要给资产
 * 加一档 P3（例如「参考素材」），任务卡片上会莫名其妙多出一个 P3 选项——
 * 这种耦合的代价远大于省下的十几行代码。</p>
 *
 * <p>两者的一致性由测试守住，而不是由共用类型强制：
 * 看板卡片上的 P0 红条与资产卡片上的 P0 红条必须是同一个红。</p>
 *
 * @author 管理员
 */
public enum TaskPriority {

    /** 最高优先级：卡住他人 / 影响里程碑 */
    P0("紧急", "#f56c6c"),

    /** 高：本迭代内需要完成 */
    P1("高", "#e6a23c"),

    /** 普通：默认值 */
    P2("普通", "#909399");

    private final String label;

    /** 卡片左侧色条颜色（文档 7.2：P0 红 / P1 橙 / P2 灰） */
    private final String color;

    TaskPriority(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }

    /** 默认优先级，与 DDL 的 DEFAULT 'P2' 保持一致 */
    public static final TaskPriority DEFAULT = P2;

    /** 字符串 → 枚举，非法返回 null */
    public static TaskPriority of(String priority) {
        if (priority == null) {
            return null;
        }
        String normalized = priority.trim().toUpperCase();
        for (TaskPriority value : values()) {
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
        TaskPriority value = of(priority);
        return value == null ? String.valueOf(priority) : value.getLabel();
    }

    /** 卡片色条颜色；未知优先级退化成默认色而不是抛异常（脏数据不该让看板打不开） */
    public static String color(String priority) {
        TaskPriority value = of(priority);
        return value == null ? DEFAULT.getColor() : value.getColor();
    }

    /** 全部合法优先级名，用于错误提示与前端下拉 */
    public static Set<String> all() {
        Set<String> result = new LinkedHashSet<>();
        for (TaskPriority value : values()) {
            result.add(value.name());
        }
        return Collections.unmodifiableSet(result);
    }
}
