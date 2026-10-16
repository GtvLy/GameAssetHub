package com.zhangchi.gameasset.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手写 SQL 的 {@code project_id} 契约测试。
 *
 * <p>模块2 绝大多数查询都用 Lambda 条件构造器，因此「SQL 是否带 project_id」
 * 可以在 {@code AssetServiceImplTest} 里直接读出来断言。
 * 但有两条语句是<b>手写注解 SQL</b>（{@link AssetVersionMapper#clearCurrent}
 * 与 {@link AssetVersionMapper#selectMaxVersionNo}），单元测试读不到它们的 SQL。</p>
 *
 * <p>这里用反射把注解里的 SQL 文本取出来做静态断言。它能挡住的是
 * 最容易发生的一类退化：后续有人为了「少传一个参数」把 {@code project_id}
 * 从这两条语句里删掉——那样越权访问就只能在数据库层靠 asset_id 兜底，
 * 而 asset_id 本身并不校验归属。</p>
 *
 * <p>同一手法也用来盯住 {@link AssetMapper#countByAssetNoIncludeDeleted}：
 * 它的 SQL <b>必须不</b>带 {@code deleted} 条件（要连逻辑删除的资产一起数）。
 * 这条一旦被「顺手加上 deleted = 0」，就回到那个会误判编号可用的老 bug 上。</p>
 *
 * <p>纯反射测试：不加载 Spring、不连 MySQL。</p>
 *
 * @author 张忠驰
 */
@DisplayName("手写 Mapper SQL 必须带 project_id")
class MapperSqlContractTest {

    /** 需要逐条核查 SQL 的 Mapper（模块2 的资产四张表 + 模块3 的任务两张表 + 模块4 的报表表） */
    private static final List<Class<?>> MAPPERS = List.of(
            AssetVersionMapper.class,
            AssetMapper.class,
            AssetCategoryMapper.class,
            AssetStatusLogMapper.class,
            TaskMapper.class,
            TaskCommentMapper.class,
            ProjectReportMapper.class);

    @Test
    @DisplayName("AssetVersionMapper 的自定义 SQL 全部带 project_id")
    void assetVersionMapperSqlIsProjectScoped() {
        List<String> checked = new ArrayList<>();
        for (Method method : AssetVersionMapper.class.getDeclaredMethods()) {
            String sql = extractSql(method);
            if (sql == null) {
                continue;
            }
            checked.add(method.getName());
            assertThat(sql)
                    .as("%s 的 SQL 必须带 project_id，否则跨项目会操作到别人的数据", method.getName())
                    .contains("project_id");
        }
        assertThat(checked)
                .as("至少要覆盖 clearCurrent 与 selectMaxVersionNo，否则本测试形同虚设")
                .contains("clearCurrent", "selectMaxVersionNo");
    }

    @Test
    @DisplayName("TaskMapper / TaskCommentMapper 的自定义 SQL 全部带 project_id")
    void taskMapperSqlIsProjectScoped() {
        List<String> checked = new ArrayList<>();
        for (Class<?> mapper : List.of(TaskMapper.class, TaskCommentMapper.class)) {
            for (Method method : mapper.getDeclaredMethods()) {
                String sql = extractSql(method);
                if (sql == null) {
                    continue;
                }
                assertThat(method.getDeclaringClass()).isEqualTo(mapper);
                checked.add(method.getName());
                assertThat(sql)
                        .as("%s 的 SQL 必须带 project_id，否则跨项目会操作到别人的数据", method.getName())
                        .contains("project_id");
            }
        }
        assertThat(checked)
                .as("至少要覆盖 updateSortOrder 与 countByTaskIds，否则本测试形同虚设")
                .contains("updateSortOrder", "countByTaskIds");
    }

    @Test
    @DisplayName("整列重排的 SQL 不得改动 version：重排不是用户发起的修改，"
            + "推进版本号会让别人手里的 version 全部作废，紧接着的拖拽莫名 409")
    void sortOrderUpdateMustNotTouchVersion() {
        Method m = findMethod(TaskMapper.class, "updateSortOrder");
        String sql = extractSql(m);
        assertThat(sql).isNotNull();
        assertThat(sql)
                .as("重排只改位置")
                .doesNotContain("version");
        assertThat(sql)
                .as("逻辑删除的行不该被重排")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("评论数批量统计的 SQL 必须显式带 deleted = 0："
            + "手写 SQL 不走 @TableLogic，漏了会把已删除的评论也算进角标")
    void commentCountMustFilterDeletedRows() {
        Method m = findMethod(TaskCommentMapper.class, "countByTaskIds");
        String sql = extractSql(m);
        assertThat(sql).isNotNull();
        assertThat(sql).contains("deleted = 0");
    }

    @Test
    @DisplayName("两个自定义方法都按 asset_id + project_id 双重定位")
    void customStatementsUseBothKeys() {
        for (String name : List.of("clearCurrent", "selectMaxVersionNo")) {
            Method method = findMethod(AssetVersionMapper.class, name);
            String sql = extractSql(method);
            assertThat(sql).as("%s 应同时按 asset_id 与 project_id 过滤", name)
                    .contains("asset_id").contains("project_id");
        }
    }

    @Test
    @DisplayName("编号查重的 SQL 不得带 deleted 过滤，否则会复用已删除资产的编号")
    void assetNoCheckMustNotFilterDeletedRows() {
        Method m = findMethod(AssetMapper.class, "countByAssetNoIncludeDeleted");
        String sql = extractSql(m);
        assertThat(sql).isNotNull().contains("project_id").contains("asset_no");
        assertThat(sql)
                .as("一旦有人给它加上 deleted = 0，被删资产的编号就会被判定为「可用」，"
                        + "而数据库的 uk_project_asset_no 并不区分删没删——插入时才撞上，"
                        + "报错还会被兜成「数据已存在，请勿重复提交」")
                .doesNotContain("deleted");
    }

    @Test
    @DisplayName("本项目所有 Mapper 都继承 BaseMapper，没有绕过条件构造器的裸 SQL")
    void allMappersExtendBaseMapper() {
        for (Class<?> mapper : MAPPERS) {
            assertThat(com.baomidou.mybatisplus.core.mapper.BaseMapper.class)
                    .as("%s 应继承 BaseMapper", mapper.getSimpleName())
                    .isAssignableFrom(mapper);
        }
    }

    // ==================== 模块4：统计与报表 ====================

    @Test
    @DisplayName("StatMapper 的每条聚合 SQL 都带 project_id")
    void statMapperSqlIsProjectScoped() {
        List<String> checked = new ArrayList<>();
        for (Method method : StatMapper.class.getDeclaredMethods()) {
            String sql = extractSql(method);
            if (sql == null) {
                continue;
            }
            checked.add(method.getName());
            assertThat(sql)
                    .as("%s 的 SQL 必须带 project_id：统计接口只按项目维度提供，"
                            + "少了它就是跨项目看别人的数字", method.getName())
                    .contains("project_id");
        }
        assertThat(checked)
                .as("统计与报表的聚合查询一条都不能少，否则本测试形同虚设")
                .contains("selectDailyRemainingPoints", "sumStoryPoint", "selectAssetStatusCount",
                        "selectCategoryStat", "selectWorkload", "selectDailyCreated",
                        "selectDailyCompleted", "countTaskCreatedBetween", "countTaskCompletedBetween",
                        "countAssetCreatedBetween", "countAssetApprovedBetween", "countAssetByStatus",
                        "selectPeriodWorkload");
    }

    @Test
    @DisplayName("燃尽图实际线必须用 task_status_log 回放：SQL 里不许出现 column_status / finished_at")
    void burndownMustReplayFromStatusLog() {
        String sql = extractSql(findMethod(StatMapper.class, "selectDailyRemainingPoints"));
        assertThat(sql).isNotNull();

        assertThat(sql)
                .as("判定「这天完成了没有」的依据只能是状态日志")
                .contains("task_status_log").contains("to_status").contains("'DONE'");
        assertThat(sql)
                .as("一旦有人把回放改成看 task.column_status，历史曲线就会随当前状态变化——"
                        + "把任务从已完成拖回待办，过去几周的燃尽图会跟着一起改，"
                        + "这正是文档 5.6 要避免的")
                .doesNotContain("column_status");
        assertThat(sql)
                .as("finished_at 同样会被拖出 DONE 时清空，用它回放有一样的问题")
                .doesNotContain("finished_at");
        assertThat(sql)
                .as("日期轴必须来自日期维表，否则没有任务的日期会缺一个点、折线断开")
                .contains("calendar_dates").contains("stat_date");
        assertThat(sql)
                .as("日期轴必须 LEFT JOIN 任务，写成 INNER JOIN 会让没有任务的日子整行消失")
                .contains("LEFT JOIN task");
    }

    @Test
    @DisplayName("燃尽图必须排除「在该日期之后才创建的任务」，否则历史点位会凭空多出故事点")
    void burndownMustExcludeTasksCreatedLater() {
        String sql = extractSql(findMethod(StatMapper.class, "selectDailyRemainingPoints"));
        assertThat(sql).isNotNull();

        assertThat(sql)
                .as("文档 5.6 的原始 SQL 只排除了已 DONE 的任务，漏掉了「未来才创建的任务」——"
                        + "一个 10 月才建的任务会在 9 月 1 日的点位上被算成剩余故事点，"
                        + "实际线整体被抬高，而图上完全看不出来")
                .contains("t.create_time").contains("DATE_ADD(d.stat_date, INTERVAL 1 DAY)");
        assertThat(sql)
                .as("用 <= stat_date 直接比会把当天创建的任务也排除掉（DATETIME 比 DATE 带零时刻），"
                        + "必须加一天再比")
                .doesNotContain("create_time <= d.stat_date");
    }

    @Test
    @DisplayName("快照写入必须是 ON DUPLICATE KEY UPDATE 一条语句，不许先查后插")
    void reportSnapshotMustBeUpsert() {
        String sql = extractSql(findMethod(ProjectReportMapper.class, "upsertSnapshot"));
        assertThat(sql).isNotNull();

        assertThat(sql)
                .as("靠 uk_project_period 的唯一约束在数据库层做幂等")
                .contains("ON DUPLICATE KEY UPDATE").contains("project_id");
        assertThat(sql)
                .as("把 INSERT 换成「先 SELECT 再决定」之后，并发下两个请求会同时查不到、同时插入；"
                        + "而 catch (DuplicateKeyException) 后在同事务里重查也看不到并发事务刚提交的行"
                        + "（REPEATABLE READ），异常最终会冒到用户面前。这条不存在 SELECT 是硬要求")
                .doesNotContain("SELECT");
    }

    @Test
    @DisplayName("报表快照的回读也带 project_id（只按类型 + 周期查会跨项目拿到别人的报表）")
    void reportSnapshotReadIsProjectScoped() {
        String sql = extractSql(findMethod(ProjectReportMapper.class, "selectSnapshot"));
        assertThat(sql).isNotNull().contains("project_id").contains("report_type");
    }

    /** 取出方法上 {@code @Select} / {@code @Update} 注解里的 SQL，没有则返回 null */
    private String extractSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            return String.join(" ", select.value());
        }
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            return String.join(" ", update.value());
        }
        return null;
    }

    private Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("找不到方法：" + type.getSimpleName() + "#" + name);
    }
}
