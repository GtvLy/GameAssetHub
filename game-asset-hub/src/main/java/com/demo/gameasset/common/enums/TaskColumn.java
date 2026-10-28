package com.demo.gameasset.common.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 看板列（文档 3.2 {@code task.column_status} 注释：TODO/DOING/REVIEW/DONE）。
 *
 * <p>列是<b>固定四列</b>而不是可配置列表：文档 7.2 的看板是
 * 「待办 / 进行中 / 待审核 / 已完成」四列一次返回，燃尽图与工作量的口径
 * 也都建立在「DONE 即完成」这个约定上。做成可配置会让这两个统计
 * 失去统一的判定依据，收益不抵成本。</p>
 *
 * <p><b>顺序就是展示顺序</b>：{@link #ordered()} 返回的顺序即看板从左到右的
 * 列顺序，服务端按它分组返回，前端不再自己排——两边各排一次，
 * 迟早有一天不一样。</p>
 *
 * @author 管理员
 */
public enum TaskColumn {

    /** 待办：已建但未开工 */
    TODO("待办", "#909399"),

    /** 进行中：正在做 */
    DOING("进行中", "#5b8cff"),

    /** 待审核：做完等待验收 */
    REVIEW("待审核", "#e6a23c"),

    /** 已完成：验收通过 */
    DONE("已完成", "#67c23a");

    /** 中文列名，看板列头展示 */
    private final String label;

    /** 列头强调色，前端直接用（避免前端自己维护一份配色） */
    private final String color;

    TaskColumn(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }

    /** 默认列，与 DDL 的 DEFAULT 'TODO' 保持一致 */
    public static final TaskColumn DEFAULT = TODO;

    /** 完成态。燃尽图、完成率、{@code finished_at} 的判定都以它为准，只此一处 */
    public static final TaskColumn FINISHED = DONE;

    /** 字符串 → 枚举，非法返回 null（大小写不敏感，容忍前端传小写） */
    public static TaskColumn of(String column) {
        if (column == null) {
            return null;
        }
        String normalized = column.trim().toUpperCase();
        for (TaskColumn value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    public static boolean isValid(String column) {
        return of(column) != null;
    }

    /** 是否完成态（{@code column_status = 'DONE'}） */
    public static boolean isFinished(String column) {
        return FINISHED == of(column);
    }

    public static String label(String column) {
        TaskColumn value = of(column);
        return value == null ? String.valueOf(column) : value.getLabel();
    }

    /**
     * 全部合法列名，按看板从上到下（左到右）的顺序。
     * 服务层的「列名非法」提示直接用它拼可选值，两边不会各写一份。
     */
    public static Set<String> all() {
        Set<String> result = new LinkedHashSet<>();
        for (TaskColumn value : values()) {
            result.add(value.name());
        }
        return Collections.unmodifiableSet(result);
    }

    /** 按展示顺序排列的全部列，服务端分组时按它走 */
    public static List<TaskColumn> ordered() {
        return Collections.unmodifiableList(Arrays.asList(values()));
    }
}
