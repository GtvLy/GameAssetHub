package com.demo.gameasset.common.enums;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 通知类型（文档 3.2 {@code notification.type} 注释 + 5.8 触发事件表）。
 *
 * <p>文档 5.8 的表格逐行对应到这里：</p>
 * <pre>
 * 任务被指派            → 被指派人            TASK_ASSIGNED
 * 任务状态变更          → 任务创建人          TASK_STATUS
 * 任务被评论            → 任务负责人          COMMENT
 * 被 @提及              → 被 @ 的人           MENTION
 * 资产审核通过或打回    → 资产负责人          ASSET_REVIEW
 * 资产提交审核          → 项目 OWNER          ASSET_REVIEW
 * 任务临近截止（前1天） → 任务负责人          TASK_DUE_SOON（定时任务）
 * </pre>
 *
 * <p>类型同时决定前端「点开通知跳到哪」的默认落点（配合 {@code biz_type}），
 * 因此这里把 {@link #bizType()} 也一并收在枚举里。</p>
 *
 * @author 管理员
 */
public enum NotificationType {

    /** 任务被指派给某人 */
    TASK_ASSIGNED("任务指派", NotificationBizType.TASK),

    /** 任务状态（看板列）发生变更 */
    TASK_STATUS("任务状态", NotificationBizType.TASK),

    /** 任务被评论 */
    COMMENT("评论", NotificationBizType.TASK),

    /** 在评论里被 @提及 */
    MENTION("提及", NotificationBizType.TASK),

    /**
     * 资产审核相关。
     * 一个类型覆盖「审核通过 / 打回」与「提交审核」两个方向：
     * 接收人不同（前者是资产负责人，后者是项目负责人），
     * 但「点开去看哪个资产」是一样的，细分类型只会让前端多一个 switch 分支。
     */
    ASSET_REVIEW("资产审核", NotificationBizType.ASSET),

    /** 任务即将到期（定时任务推送，文档 5.8 末尾） */
    TASK_DUE_SOON("即将到期", NotificationBizType.TASK);

    private final String label;

    /** 默认跳转的业务类型，写入 {@code notification.biz_type} */
    private final String bizType;

    NotificationType(String label, String bizType) {
        this.label = label;
        this.bizType = bizType;
    }

    public String getLabel() {
        return label;
    }

    public String getBizType() {
        return bizType;
    }

    /** 字符串 → 枚举，非法返回 null */
    public static NotificationType of(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.trim().toUpperCase();
        for (NotificationType value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    public static String label(String type) {
        NotificationType value = of(type);
        return value == null ? String.valueOf(type) : value.getLabel();
    }

    public static Set<String> all() {
        Set<String> result = new LinkedHashSet<>();
        for (NotificationType value : values()) {
            result.add(value.name());
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 通知可跳转的业务类型（{@code notification.biz_type}）。
     *
     * <p>单独建一个常量类而不是用裸字符串：前端要按它分发跳转，
     * 拼错一个字母的表现是「点了通知没反应」，且不会有任何报错。</p>
     */
    public static final class NotificationBizType {

        /** 任务：跳任务详情 */
        public static final String TASK = "TASK";

        /** 资产：跳资产详情 */
        public static final String ASSET = "ASSET";

        /** 项目：跳项目概览 */
        public static final String PROJECT = "PROJECT";

        private NotificationBizType() {
        }
    }
}
