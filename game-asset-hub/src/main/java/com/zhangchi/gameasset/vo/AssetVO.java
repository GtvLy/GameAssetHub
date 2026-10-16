package com.zhangchi.gameasset.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 资产详情 / 列表项（文档 7.3 的卡片与表格视图共用同一份数据）。
 *
 * <p>列表页与详情页返回同一个 VO，不另做「精简版」：
 * 卡片要缩略图与状态、表格要版本数与负责人、详情要当前版本与审核信息，
 * 三者的字段集合并集很小（十几个字段），为省这点体积拆两个 VO
 * 只会带来「列表页点进详情还要再请求一次」的额外等待。</p>
 *
 * @author 张忠驰
 */
@Data
public class AssetVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long projectId;

    private Long categoryId;

    /** 分类名，列表与卡片上展示 */
    private String categoryName;

    /** 分类编码 */
    private String categoryCode;

    /** 分类主题色，卡片左上角状态色块之外的分类标识用它 */
    private String categoryColor;

    /** 资产编号，如 CHR_SM_0001 */
    private String assetNo;

    private String name;

    private String description;

    /** 当前版本ID */
    private Long currentVersionId;

    private Integer versionCount;

    /** IN_PROGRESS / PENDING / APPROVED / REJECTED / DEPRECATED */
    private String status;

    /** 状态中文名 */
    private String statusLabel;

    /** P0 / P1 / P2 */
    private String priority;

    private String priorityLabel;

    /** 标签原串（逗号分隔） */
    private String tags;

    /** 标签数组，前端直接遍历渲染，不必各自 split */
    private List<String> tagList = new ArrayList<>();

    /** 主负责人 */
    private Long ownerId;

    private String ownerName;

    /** 审核人 */
    private Long reviewerId;

    private String reviewerName;

    /** 审核意见（打回原因） */
    private String reviewRemark;

    private LocalDateTime reviewTime;

    /**
     * 当前状态下允许流转到的状态集合。
     *
     * <p>由服务端下发而不是前端硬编码状态机：文档 6 节要求
     * 「状态流转可配置，调整工作流只改一处」。前端自己维护一份流转表，
     * 后端改了规则而前端没跟上时，用户会看到一个点了必报 400 的按钮。</p>
     */
    private List<String> nextStatuses = new ArrayList<>();

    /** 当前版本的完整信息（详情页用；列表页为 null 以减小响应体积由调用方决定） */
    private AssetVersionVO currentVersion;

    /** 当前版本的缩略图，卡片墙直接取用，避免前端再钻一层 */
    private String thumbnailUrl;

    /** 最近一次上传人昵称（列表「更新时间」旁边展示协作痕迹） */
    private String lastUploaderName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 相关任务（模块3 交付，文档 5.5：资产详情页的「相关任务」区块）。
     *
     * <p><b>只在资产详情里填充</b>，列表接口为 null。列表一页 20 条资产，
     * 每条都去查一次关联任务就是 20 次额外查询，而列表上并不展示这些信息。</p>
     *
     * <p>字段级 {@code @JsonInclude(NON_NULL)} 是刻意加的：项目全局配置是
     * {@code default-property-inclusion: always}，若不用它，列表响应里会多出
     * 一个恒为 null 的字段——模块2 的验证脚本对列表响应的字段集是有断言的，
     * 不必要地改宽接口契约不如不动它。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<TaskCardVO> relatedTasks;
}
