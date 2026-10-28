package com.demo.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.gameasset.entity.ProjectReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 项目报表快照 Mapper（模块4）。
 *
 * @author 管理员
 */
@Mapper
public interface ProjectReportMapper extends BaseMapper<ProjectReport> {

    /**
     * 写入 / 更新周报快照（幂等 upsert）。
     *
     * <h3>为什么必须是 {@code ON DUPLICATE KEY UPDATE} 一条语句</h3>
     * <p>「同一项目 + 同类型 + 同周期」靠 {@code uk_project_period} 保证唯一。
     * 先 {@code SELECT} 再决定 {@code INSERT} 还是 {@code UPDATE} 的写法在并发下必然出问题：
     * 两个请求同时查到「没有」，同时插入，必然有一个撞唯一键。</p>
     *
     * <p>而「撞唯一键之后 catch 住异常、在<b>同一个事务里</b>重新查一次再把已有行更新掉」
     * 这个看起来很自然的兜底是<b>坏的</b>：MySQL 默认 REPEATABLE READ，
     * 事务的一致性快照在第一次查重（返回空）时就已经固定，
     * 那次重查看不到并发事务刚刚提交的行——重查又是空，
     * 异常最终冒到用户面前。本轮在项目02 的点名幂等上真实踩过这个坑。</p>
     *
     * <p>交给 {@code ON DUPLICATE KEY UPDATE} 之后，既不重查、也不依赖异常，
     * 一条语句就是原子的：第一次生成是插入，重复生成是更新，两次都返回成功。
     * 「重复生成同一周期不报错、且快照被刷新」这条行为因此可以被端到端脚本直接钉住。</p>
     *
     * <p>{@code VALUES(col)} 取的是本条 INSERT 里给出的值。MySQL 8.0.20 起标记为
     * 过时（推荐 {@code AS new} 别名写法），但过时写法在全部 8.0.x 上都仍可用，
     * 而别名写法在 8.0.19 以下直接语法错误——这里取兼容性更好的一种。</p>
     *
     * @return 受影响行数：插入为 1，更新为 2，内容完全未变为 0
     */
    @Update("INSERT INTO project_report "
            + "(project_id, report_type, period_start, period_end, snapshot_json) "
            + "VALUES (#{projectId}, #{reportType}, #{periodStart}, #{periodEnd}, #{snapshotJson}) "
            + "ON DUPLICATE KEY UPDATE period_end = VALUES(period_end), "
            + "snapshot_json = VALUES(snapshot_json)")
    int upsertSnapshot(@Param("projectId") Long projectId,
                       @Param("reportType") String reportType,
                       @Param("periodStart") LocalDate periodStart,
                       @Param("periodEnd") LocalDate periodEnd,
                       @Param("snapshotJson") String snapshotJson);

    /**
     * 读回已存的快照（供「历史上生成过哪些周期的报表」这类查询使用）。
     *
     * <p>查询带 {@code project_id}：越权防护的第二道条件在这里同样不能省，
     * 只按 {@code report_type + period_start} 查会跨项目拿到别人的报表。</p>
     */
    @Select("SELECT * FROM project_report "
            + "WHERE project_id = #{projectId} AND report_type = #{reportType} "
            + "  AND period_start = #{periodStart}")
    ProjectReport selectSnapshot(@Param("projectId") Long projectId,
                                 @Param("reportType") String reportType,
                                 @Param("periodStart") LocalDate periodStart);
}
