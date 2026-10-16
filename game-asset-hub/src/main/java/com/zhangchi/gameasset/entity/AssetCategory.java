package com.zhangchi.gameasset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资产分类（{@code asset_category}，文档 3.2 DDL）。
 *
 * <p>支持二级：{@code parentId = 0} 为一级分类，非 0 表示挂在某个一级分类下。</p>
 *
 * <p>本表<b>没有 {@code deleted} 列</b>：分类删除是物理删除，
 * 且删除前必须确认分类下没有资产（有则拒绝），
 * 因此不存在「删了分类留下孤儿资产」的中间态，也就不需要软删标志位。</p>
 *
 * @author 张忠驰
 */
@Data
@TableName("asset_category")
public class AssetCategory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 一级分类的 parentId 约定值 */
    public static final Long ROOT_PARENT_ID = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目，所有查询都必须带这个条件 */
    private Long projectId;

    /** 父分类ID，0 表示一级分类 */
    private Long parentId;

    /** 分类名：模型 / 贴图 / 蓝图 / 音效 / 动画 / UI */
    private String name;

    /**
     * 分类编码，用于自动生成资产编号（如 {@code CHR_SM}）。
     * 同一项目内唯一（{@code uk_project_code}）。
     */
    private String code;

    /** 图标名（Element Plus 图标名），仅前端展示用 */
    private String icon;

    /** 主题色，默认 #409EFF */
    private String color;

    /** 排序值，升序展示 */
    private Integer sortOrder;

    private LocalDateTime createTime;
}
