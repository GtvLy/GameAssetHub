package com.demo.gameasset.common.enums;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 任务类型（文档 3.2 {@code task.task_type} 注释：
 * ART美术 / PROGRAM程序 / DESIGN策划 / BUG缺陷 / OTHER其它）。
 *
 * <p>与 {@link ProjectRole} 的 ARTIST / PROGRAMMER / DESIGNER 一一呼应，
 * 但两者是不同概念、不共用枚举：任务类型描述的是「这件事属于哪条线」，
 * 项目角色描述的是「这个人能做什么」。一个程序角色完全可以接一个美术任务。</p>
 *
 * <p>前端按类型给卡片打分类标签，因此这里也带上了展示色，
 * 避免同一套配色在前后端各维护一份。</p>
 *
 * @author 管理员
 */
public enum TaskType {

    /** 美术：模型 / 贴图 / 动画 / 特效 */
    ART("美术", "#5b8cff"),

    /** 程序：逻辑 / 工具 / 性能 */
    PROGRAM("程序", "#67c23a"),

    /** 策划：玩法 / 数值 / 关卡 */
    DESIGN("策划", "#e6a23c"),

    /** 缺陷：需要修复的问题 */
    BUG("缺陷", "#f56c6c"),

    /** 其它：不属于上面任何一条线 */
    OTHER("其它", "#909399");

    private final String label;

    /** 标签色，前端渲染任务卡片的类型标签 */
    private final String color;

    TaskType(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }

    /** 默认类型，与 DDL 的 DEFAULT 'ART' 保持一致 */
    public static final TaskType DEFAULT = ART;

    /** 字符串 → 枚举，非法返回 null */
    public static TaskType of(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.trim().toUpperCase();
        for (TaskType value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    public static boolean isValid(String type) {
        return of(type) != null;
    }

    public static String label(String type) {
        TaskType value = of(type);
        return value == null ? String.valueOf(type) : value.getLabel();
    }

    /** 全部合法类型名，用于错误提示与前端下拉 */
    public static Set<String> all() {
        Set<String> result = new LinkedHashSet<>();
        for (TaskType value : values()) {
            result.add(value.name());
        }
        return Collections.unmodifiableSet(result);
    }
}
