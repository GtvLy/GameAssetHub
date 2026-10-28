package com.demo.gameasset.dto;

import lombok.Data;

/**
 * 通知列表查询参数（文档 4.2：分页，可筛未读）。
 *
 * @author 管理员
 */
@Data
public class NotificationQueryDTO {

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数 */
    private Integer size = 20;

    /** 只看未读（铃铛下拉的默认视图） */
    private Boolean unreadOnly;

    /** 归一化页码 */
    public int normalizedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    /** 归一化页大小 */
    public int normalizedSize() {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 200);
    }

    public boolean onlyUnread() {
        return Boolean.TRUE.equals(unreadOnly);
    }
}
