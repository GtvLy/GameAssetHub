package com.zhangchi.gameasset.controller;

import com.zhangchi.gameasset.common.annotation.ProjectId;
import com.zhangchi.gameasset.common.annotation.ProjectPermission;
import com.zhangchi.gameasset.common.enums.Permission;
import com.zhangchi.gameasset.common.result.Result;
import com.zhangchi.gameasset.service.ReportService;
import com.zhangchi.gameasset.service.StatService;
import com.zhangchi.gameasset.service.support.StatExportFile;
import com.zhangchi.gameasset.vo.AssetCategoryStatVO;
import com.zhangchi.gameasset.vo.AssetCompletionVO;
import com.zhangchi.gameasset.vo.BurndownVO;
import com.zhangchi.gameasset.vo.ReportVO;
import com.zhangchi.gameasset.vo.TaskTrendVO;
import com.zhangchi.gameasset.vo.WorkloadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 统计与进度可视化接口（模块4，文档 4.4）。
 *
 * <h3>八个接口全在 {@code /api/projects/{pid}/...} 下，项目ID 一律来自路径</h3>
 * <p>不采用 {@code /api/stat/*} 这种「路径里没有项目段」的形态。
 * 原因是路径里的 {@code pid} 是越权重灾区：一旦路径中没有项目段，
 * 项目上下文就只能靠 {@code X-Project-Id} 请求头，而请求头是客户端
 * 说了算的——解析器虽然会拿它去查成员关系、非成员会 403，
 * 但「越权与否取决于一个客户端可任意改写的头」这件事本身就不该出现在
 * 只读统计接口上。路径里写死 pid，越权请求在路由层就落了空。</p>
 *
 * <h3>权限矩阵（模块4 新增 STAT_READ 这一格的落点）</h3>
 * <pre>
 * 角色           统计查看（STAT_READ）  导出
 * OWNER          ✅                      ✅
 * ARTIST         ✅                      ✅
 * PROGRAMMER     ✅                      ✅
 * DESIGNER       ✅                      ✅
 * VIEWER         ❌（403）              ❌（403）
 * </pre>
 * <p>这张表不是在这里写 if 判断出来的，就是 {@code RolePermissionMap} 里
 * 已有的 {@code STAT_READ} 一格——文档 3.3 的权限矩阵「统计查看」列对
 * VIEWER 明确是 ❌，{@code RolePermissionMap} 与前端 {@code roleMeta}
 * 的描述都照此实现，本模块沿用，不另立规则。</p>
 *
 * <p>导出与查询<b>同权限</b>：能看报表就能导出报表，导出不是额外的特权——
 * 把导出单列成更高权限只会让 OWNER 之外的人先截图再手工贴进 Excel，
 * 既没防住泄露，又平白多了一道摩擦。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StatController {

    private final StatService statService;
    private final ReportService reportService;

    // ==================== 统计图表 ====================

    /** 资产完成率与状态分布（环形图） */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/stat/asset-completion")
    public Result<AssetCompletionVO> assetCompletion(@PathVariable("projectId") @ProjectId Long projectId) {
        return Result.success(statService.assetCompletion(projectId));
    }

    /** 各分类资产数量与完成情况（柱状图） */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/stat/asset-by-category")
    public Result<List<AssetCategoryStatVO>> assetByCategory(@PathVariable("projectId") @ProjectId Long projectId) {
        return Result.success(statService.assetByCategory(projectId));
    }

    /** 燃尽图：理想线 + 用 {@code task_status_log} 回放的实际线 */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/stat/burndown")
    public Result<BurndownVO> burndown(@PathVariable("projectId") @ProjectId Long projectId) {
        return Result.success(statService.burndown(projectId));
    }

    /** 成员工作量与负载均衡度 */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/stat/workload")
    public Result<WorkloadVO> workload(@PathVariable("projectId") @ProjectId Long projectId) {
        return Result.success(statService.workload(projectId));
    }

    /**
     * 任务新增 / 完成趋势（折线图）。
     *
     * @param days 窗口天数，取值 [7, 180]，缺省 30；越界返回 400
     */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/stat/task-trend")
    public Result<TaskTrendVO> taskTrend(@PathVariable("projectId") @ProjectId Long projectId,
                                         @RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statService.taskTrend(projectId, days));
    }

    // ==================== 报表 ====================

    /**
     * 项目周报。
     *
     * <p>重复生成同一周期是幂等的：不报错，{@code project_report} 里的快照被刷新。</p>
     *
     * @param weekStart 周期起始日 {@code yyyy-MM-dd}；缺省取本周一
     */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/report/weekly")
    public Result<ReportVO> weekly(@PathVariable("projectId") @ProjectId Long projectId,
                                   @RequestParam(value = "weekStart", required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return Result.success(reportService.weekly(projectId, weekStart));
    }

    /**
     * 项目月报。
     *
     * @param month {@code yyyy-MM}；缺省取当前月，格式非法返回 400
     */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/report/monthly")
    public Result<ReportVO> monthly(@PathVariable("projectId") @ProjectId Long projectId,
                                    @RequestParam(value = "month", required = false) String month) {
        return Result.success(reportService.monthly(projectId, month));
    }

    // ==================== 导出 ====================

    /**
     * 导出统计报表 xlsx。
     *
     * <p>返回的是<b>二进制流</b>而不是统一响应体 {@code Result}：
     * Excel 要能被浏览器/客户端直接保存成文件，包一层 JSON 就没法打开了。
     * 出错时仍然走全局异常处理器返回 {@code Result}（HTTP 4xx/5xx），
     * 所以前端必须先看 HTTP 状态码再决定是「保存文件」还是「提示错误」——
     * 这也是前端用 axios 的 {@code responseType: 'blob'} 而不是
     * {@code window.open} 的原因之一：{@code window.open} 既带不上
     * {@code Authorization} 头，也无法区分「下到了一个 xlsx」和
     * 「下到了一个写着 403 的 JSON」。</p>
     *
     * <p><b>中文文件名的处理</b>：HTTP 头只能是 ASCII，
     * 中文文件名必须走 RFC 5987 的 {@code filename*=utf-8''<百分号编码>} 形式。
     * 同时保留一个 ASCII 的 {@code filename=} 兜底，供不认识 {@code filename*}
     * 的老客户端使用（写英文名，避免它按 Latin-1 解析出乱码文件名）。</p>
     */
    @ProjectPermission(Permission.STAT_READ)
    @GetMapping("/api/projects/{projectId}/stat/export")
    public ResponseEntity<byte[]> export(@PathVariable("projectId") @ProjectId Long projectId) {
        StatExportFile file = statService.export(projectId);

        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        String disposition = "attachment;filename=\"stat-report.xlsx\";"
                + "filename*=utf-8''" + encoded;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(file.content().length)
                .body(file.content());
    }
}
