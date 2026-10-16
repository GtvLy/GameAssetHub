package com.zhangchi.gameasset.common.enums;

import java.util.Set;

/**
 * 项目状态（{@code project.status}）。
 *
 * @author 张忠驰
 */
public final class ProjectStatus {

    /** 进行中 */
    public static final String ACTIVE = "ACTIVE";

    /** 已暂停 */
    public static final String PAUSED = "PAUSED";

    /** 已归档 */
    public static final String ARCHIVED = "ARCHIVED";

    /** 允许的状态集合，供 PATCH /{id}/status 校验 */
    private static final Set<String> ALL = Set.of(ACTIVE, PAUSED, ARCHIVED);

    private ProjectStatus() {
    }

    public static boolean isValid(String status) {
        return status != null && ALL.contains(status);
    }

    public static Set<String> all() {
        return ALL;
    }

    public static String label(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case ACTIVE -> "进行中";
            case PAUSED -> "已暂停";
            case ARCHIVED -> "已归档";
            default -> status;
        };
    }
}
