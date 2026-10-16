package com.zhangchi.gameasset.common.enums;

import lombok.Getter;

/**
 * 项目级权限点。
 *
 * <p>权限点与角色的对应关系<b>只在 {@link RolePermissionMap} 一处定义</b>，
 * 业务代码里不许出现 {@code "OWNER".equals(role)} 这类散落的 if-else，
 * 否则新增角色或调整权限矩阵时要满仓库找判断。</p>
 *
 * @author 张忠驰
 */
@Getter
public enum Permission {

    /** 查看项目信息 / 概览 */
    PROJECT_READ("查看项目", "项目设置"),

    /** 修改项目信息、变更项目状态 */
    PROJECT_WRITE("修改项目", "项目设置"),

    /** 邀请成员、调整成员角色、移除成员 */
    MEMBER_MANAGE("成员管理", "项目设置"),

    /** 查看资产与版本历史 */
    ASSET_READ("查看资产", "资产管理"),

    /** 新建资产、上传新版本、提交审核 */
    ASSET_WRITE("编辑资产", "资产管理"),

    /** 资产审核（通过 / 打回 / 废弃） */
    ASSET_REVIEW("资产审核", "状态审核"),

    /** 删除资产（逻辑删除） */
    ASSET_DELETE("删除资产", "资产管理"),

    /** 查看任务看板 */
    TASK_READ("查看看板", "任务看板"),

    /** 创建 / 修改 / 拖拽任务 */
    TASK_WRITE("编辑任务", "任务看板"),

    /** 查看统计图表与导出报表 */
    STAT_READ("查看统计", "统计查看");

    /** 中文名称，用于前端渲染权限说明与错误提示 */
    private final String label;

    /** 所属功能域，对应文档 3.3 权限矩阵的列 */
    private final String domain;

    Permission(String label, String domain) {
        this.label = label;
        this.domain = domain;
    }
}
