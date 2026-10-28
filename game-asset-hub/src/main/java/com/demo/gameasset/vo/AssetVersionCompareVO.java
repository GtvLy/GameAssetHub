package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 两个版本的元数据对比结果（文档 4.2 {@code GET /api/assets/{id}/versions/compare}）。
 *
 * <p><b>只比元数据，不比文件内容</b>：三维资产的差异是几何与贴图，
 * 逐字节 diff 没有可读性；美术真正需要确认的是「面数涨了多少」
 * 「贴图从 1K 变 2K 还是反过来」「谁在什么时候改的、说明写了什么」。
 * 因此这里返回一组结构化差异，前端渲染成对比表。</p>
 *
 * @author 管理员
 */
@Data
public class AssetVersionCompareVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long assetId;

    /** 对比基准版本（旧） */
    private AssetVersionVO from;

    /** 对比目标版本（新） */
    private AssetVersionVO to;

    /** 逐项差异 */
    private List<DiffItem> diffs = new ArrayList<>();

    /** 面数变化（to - from），任一版本无面数时为 null */
    private Integer polyCountDelta;

    /** 体积变化（字节，to - from） */
    private Long fileSizeDelta;

    /** 体积变化的人话描述，如「+128.4 MB」 */
    private String fileSizeDeltaText;

    /**
     * 单项差异。
     *
     * @param field  字段名（中文，直接展示，不必前端再维护一份映射）
     * @param before 旧值（已转成字符串，null 显示为「—」）
     * @param after  新值
     * @param changed 是否发生变化
     */
    public record DiffItem(String field, String before, String after, boolean changed) {
    }
}
