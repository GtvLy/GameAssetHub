package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 整块看板（文档 4.2 {@code GET /api/projects/{pid}/board}）。
 *
 * <p><b>一次请求返回四列</b>，而不是四列四次请求：文档 6 节要求
 * 「看板拖拽 ≤ 500ms」，四次往返在一个来回 100ms 的环境下光网络就吃掉一半；
 * 而且四列分四次取，加载过程中会出现「两列有新数据、两列还是旧的」的中间态，
 * 用户拖拽时看到的排序基准可能是错的。</p>
 *
 * <p>四列<b>恒定返回</b>（哪怕某列一张卡片都没有）：前端直接按
 * {@code columns} 渲染四列，不必自己补空列——空列判定散落在前端，
 * 迟早会出现「某列不见了」的问题。</p>
 *
 * @author 张忠驰
 */
@Data
public class BoardVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long projectId;

    /** 全部任务数（四列之和） */
    private Integer totalTasks;

    /** 按看板从左到右的顺序：TODO / DOING / REVIEW / DONE，恒定四项 */
    private List<BoardColumnVO> columns = new ArrayList<>();
}
