package com.demo.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 看板的一列（文档 7.2：列头显示数量与故事点合计）。
 *
 * <p>数量与故事点合计由服务端算好下发，而不是让前端遍历卡片求：
 * 一来「未填故事点算 0」这类口径只有一处定义；
 * 二来列头在卡片很多时要立刻显示，前端遍历只是白等。</p>
 *
 * @author 管理员
 */
@Data
public class BoardColumnVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** TODO / DOING / REVIEW / DONE */
    private String status;

    /** 中文列名：待办 / 进行中 / 待审核 / 已完成 */
    private String title;

    /** 列头强调色 */
    private String color;

    /** 本列卡片数 */
    private Integer taskCount;

    /** 本列故事点合计（未填故事点的任务按 0 计） */
    private Integer storyPointTotal;

    /** 本列卡片，按 sort_order ASC, id ASC 排列 */
    private List<TaskCardVO> tasks = new ArrayList<>();
}
