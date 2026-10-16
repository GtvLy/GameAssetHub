package com.zhangchi.gameasset.service;

import com.zhangchi.gameasset.service.support.StatExportFile;
import com.zhangchi.gameasset.vo.AssetCategoryStatVO;
import com.zhangchi.gameasset.vo.AssetCompletionVO;
import com.zhangchi.gameasset.vo.BurndownVO;
import com.zhangchi.gameasset.vo.TaskTrendVO;
import com.zhangchi.gameasset.vo.WorkloadVO;

import java.util.List;

/**
 * 统计与进度可视化（模块4，文档 4.4 的 {@code /api/projects/{pid}/stat/**}）。
 *
 * <p>所有方法都要求调用者是该项目的 ACTIVE 成员：控制器上的
 * {@code @ProjectPermission(Permission.STAT_READ)} 是第一道，
 * 实现里的 {@code ProjectAccessGuard} 是第二道（Service 可能被非 HTTP
 * 入口调用，那时切面不生效）。</p>
 *
 * @author 张忠驰
 */
public interface StatService {

    /** 资产完成率与状态分布（环形图） */
    AssetCompletionVO assetCompletion(Long projectId);

    /** 各分类资产数量与完成情况（柱状图） */
    List<AssetCategoryStatVO> assetByCategory(Long projectId);

    /** 燃尽图（理想线 + 用状态日志回放的实际线） */
    BurndownVO burndown(Long projectId);

    /** 成员工作量与负载均衡度 */
    WorkloadVO workload(Long projectId);

    /**
     * 任务新增 / 完成趋势。
     *
     * @param days 窗口天数，取值 [7, 180]；为 null 时取默认 30
     */
    TaskTrendVO taskTrend(Long projectId, Integer days);

    /** 导出统计报表 xlsx（文件名 + 字节内容，写响应头是控制器的事） */
    StatExportFile export(Long projectId);
}
