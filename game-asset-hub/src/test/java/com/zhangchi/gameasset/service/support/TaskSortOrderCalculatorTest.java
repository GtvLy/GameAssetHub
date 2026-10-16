package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.entity.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看板拖拽落位算法测试（文档 3.3 第 3 点 + 9 节的「首/中/末三种位置」）。
 *
 * <p>这是模块3 最容易出错的一段：算错的表现是「两张卡片排序值相同」，
 * 而看板照样能打开，只是刷新一下顺序可能变、并且再也拖不回正确位置。
 * 真机演示时很难一眼看出来，所以这里逐格覆盖。</p>
 *
 * <p><b>断言的是「写回之后列内长什么样」</b>，而不是只看返回的那个排序值：
 * 整列重排会同时改其他卡片的位置，只检查被拖动的那一张，
 * 正好漏掉「重排把别人排乱了」这类错误。{@code appliedOrder} 就是
 * 真实环境里「把结果写回库、再按 sort_order, id 查一次」的等价物。</p>
 *
 * <p>纯逻辑测试：不加载 Spring、不连数据库。</p>
 *
 * @author 张忠驰
 */
@DisplayName("TaskSortOrderCalculator 拖拽落位")
class TaskSortOrderCalculatorTest {

    /** 造一张卡片，只有 id 与 sortOrder 参与算法 */
    private static Task task(long id, int sortOrder) {
        Task t = new Task();
        t.setId(id);
        t.setSortOrder(sortOrder);
        return t;
    }

    private static List<Task> column(Task... tasks) {
        return new ArrayList<>(List.of(tasks));
    }

    /**
     * 把一次落位的结果应用到列上，返回列内最终的 id 顺序。
     *
     * <p>等价于：把重排值与自身的落位值写回数据库，再执行
     * {@code SELECT ... ORDER BY sort_order ASC, id ASC}。
     * 被拖动的卡片在 {@code place()} 里本来就被排除，这里单独补上它。</p>
     */
    private static List<Long> appliedOrder(List<Task> columnTasks, long movingId,
                                           TaskSortOrderCalculator.Placement placement) {
        Map<Long, Integer> orders = new HashMap<>();
        for (Task t : columnTasks) {
            orders.put(t.getId(), t.getSortOrder());
        }
        orders.putAll(placement.getReorders());
        orders.put(movingId, placement.getSortOrder());

        List<Long> ids = new ArrayList<>(orders.keySet());
        ids.sort(Comparator.<Long>comparingInt(orders::get).thenComparingLong(Long::longValue));
        return ids;
    }

    // ==================== 三种位置 ====================

    @Test
    @DisplayName("队首：prev 为空、next 是第一张 → next 的排序值减一个步长")
    void placeAtHead() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 2000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, null, 2L);

        assertThat(placement.getSortOrder()).isEqualTo(0);
        assertThat(placement.isRebalanced()).isFalse();
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(99L, 2L, 3L);
    }

    @Test
    @DisplayName("队尾：next 为空、prev 是最后一张 → prev 的排序值加一个步长")
    void placeAtTail() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 2000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 3L, null);

        assertThat(placement.getSortOrder()).isEqualTo(3000);
        assertThat(placement.isRebalanced()).isFalse();
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(2L, 3L, 99L);
    }

    @Test
    @DisplayName("中间：取前后两张的中间值，且严格落在两者之间")
    void placeInMiddle() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 3000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 2L, 3L);

        assertThat(placement.getSortOrder()).isEqualTo(2000);
        assertThat(placement.isRebalanced()).isFalse();
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(2L, 99L, 3L);
    }

    @Test
    @DisplayName("空列：落位值为 1000（排序值从 1×STEP 起，0 留给「还没排过序的行」）")
    void placeIntoEmptyColumn() {
        var placement = TaskSortOrderCalculator.place(new ArrayList<>(), 99L, null, null);

        assertThat(placement.getSortOrder()).isEqualTo(TaskSortOrderCalculator.STEP);
        assertThat(placement.isRebalanced()).isFalse();
        assertThat(placement.getReorders()).isEmpty();
    }

    // ==================== 间距耗尽 → 整列重排 ====================

    @Test
    @DisplayName("相邻间距小于 10 时整列重排：间隔恢复 1000，且移动的那张落在正确位置")
    void rebalancesWhenGapTooSmall() {
        // 1000 与 1005 之间塞不下第三张卡片（再取中点会等于其中一边）
        List<Task> columnTasks = column(task(2, 1000), task(3, 1005));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 2L, 3L);

        assertThat(placement.isRebalanced()).isTrue();
        assertThat(placement.getSortOrder()).isEqualTo(2000);
        assertThat(placement.getReorders()).containsOnly(entry(2L, 1000), entry(3L, 3000));
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(2L, 99L, 3L);
    }

    @Test
    @DisplayName("重排后同一列内不存在两个相同的排序值，且位置与重排前一致")
    void rebalanceKeepsPositionsAndUniqueness() {
        // 四张卡片挤在同一个小区间里（长时间拖拽后的典型状态）
        List<Task> columnTasks = column(
                task(2, 1000), task(3, 1002), task(4, 1004), task(5, 1006));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 4L, 5L);

        assertThat(placement.isRebalanced()).isTrue();
        assertThat(appliedOrder(columnTasks, 99L, placement))
                .as("重排只把间隔恢复成 1000，不能被重排出别的顺序")
                .containsExactly(2L, 3L, 4L, 99L, 5L);

        List<Integer> orders = new ArrayList<>(placement.getReorders().values());
        orders.add(placement.getSortOrder());
        assertThat(orders).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("库里已有重复排序值时，落位会顺手重排整列治好它，而不是把重复继续传下去")
    void healsDuplicateSortOrders() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 1000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 2L, 3L);

        assertThat(placement.isRebalanced()).isTrue();
        List<Integer> orders = new ArrayList<>(placement.getReorders().values());
        orders.add(placement.getSortOrder());
        assertThat(orders).doesNotHaveDuplicates();
    }

    // ==================== prev / next 认不出来时的兜底 ====================

    @Test
    @DisplayName("prev 已被他人移走（不在目标列）→ 不报错，按 next 定位")
    void toleratesPrevMovedAway() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 2000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 777L, 2L);

        // prev 认不出来，退到「插在 next 前面」= 队首
        assertThat(placement.getSortOrder()).isEqualTo(0);
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(99L, 2L, 3L);
    }

    @Test
    @DisplayName("next 不存在 → 不报错，按 prev 定位到它后面")
    void toleratesMissingNext() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 2000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 3L, 888L);

        assertThat(placement.getSortOrder()).isEqualTo(3000);
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(2L, 3L, 99L);
    }

    @Test
    @DisplayName("prev 与 next 都不认识 → 追加到列尾，而不是抛异常")
    void toleratesBothUnknown() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 2000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 777L, 888L);

        assertThat(placement.getSortOrder()).isEqualTo(3000);
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(2L, 3L, 99L);
    }

    @Test
    @DisplayName("prev 与 next 不相邻（中间夹着别人刚拖进去的卡片）→ 不会算出与中间那张相同的值")
    void toleratesNonAdjacentAnchors() {
        // 客户端以为 2 后面就是 3，但 4 已经被别人插到了它们之间
        List<Task> columnTasks = column(task(2, 1000), task(4, 2000), task(3, 3000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 99L, 2L, 3L);

        // 取「2 后面的第一张」（此刻是 4）作为 next，而不是盲取 2 与 3 的中点 2000
        assertThat(placement.getSortOrder()).isNotEqualTo(2000);
        assertThat(placement.isRebalanced()).isFalse();
        assertThat(appliedOrder(columnTasks, 99L, placement)).containsExactly(2L, 99L, 4L, 3L);
    }

    @Test
    @DisplayName("被移动的卡片本来就在目标列里：不会把它自己当成邻居")
    void excludesMovingTaskFromNeighbors() {
        // 卡片 3 已在列内，现在要把它拖到 2 的前面
        List<Task> columnTasks = column(task(2, 1000), task(3, 2000));
        var placement = TaskSortOrderCalculator.place(columnTasks, 3L, null, 2L);

        assertThat(placement.getSortOrder()).isEqualTo(0);
        assertThat(appliedOrder(columnTasks, 3L, placement)).containsExactly(3L, 2L);
    }

    // ==================== 列内顺序本身 ====================

    @Test
    @DisplayName("列内顺序：sort_order 升序；相等时按 id 升序（与查询 SQL 的次排序键一致）")
    void ordersBySortOrderThenId() {
        List<Task> ordered = TaskSortOrderCalculator.inColumnOrder(
                column(task(9, 2000), task(3, 1000), task(5, 1000)), null);

        assertThat(ordered.stream().map(Task::getId)).containsExactly(3L, 5L, 9L);
    }

    @Test
    @DisplayName("sort_order 为 null 视为 0（防御脏数据），不抛 NPE")
    void toleratesNullSortOrder() {
        Task noOrder = task(7, 0);
        noOrder.setSortOrder(null);
        List<Task> ordered = TaskSortOrderCalculator.inColumnOrder(
                column(task(9, 2000), noOrder), null);

        assertThat(ordered.stream().map(Task::getId)).containsExactly(7L, 9L);
    }

    @Test
    @DisplayName("入参为 null 时返回空列而不是 NPE（新建任务时的空看板）")
    void toleratesNullColumn() {
        assertThat(TaskSortOrderCalculator.inColumnOrder(null, null)).isEmpty();
        var placement = TaskSortOrderCalculator.place(null, 99L, null, null);
        assertThat(placement.getSortOrder()).isEqualTo(TaskSortOrderCalculator.STEP);
    }

    // ==================== 连续拖拽 ====================

    @Test
    @DisplayName("同一张卡片在列内连续上下移动八次：每次落位都不与任何卡片重复")
    void repeatedMovesNeverCollide() {
        List<Task> columnTasks = column(task(2, 1000), task(3, 2000), task(4, 3000));

        // 每次拖拽的前后锚点，0 表示「没有这一侧」（队首 / 队尾）。
        // 刻意在同一个小区域里反复上下来回，把相邻间距磨平以触发整列重排
        int[][] moves = {
                {0, 2}, {2, 3}, {3, 4}, {2, 4}, {0, 3}, {3, 4}, {2, 3}, {0, 2}
        };
        for (int i = 0; i < moves.length; i++) {
            Long prevId = moves[i][0] == 0 ? null : (long) moves[i][0];
            Long nextId = moves[i][1] == 0 ? null : (long) moves[i][1];
            var placement = TaskSortOrderCalculator.place(columnTasks, 99L, prevId, nextId);

            List<Integer> orders = new ArrayList<>(placement.getReorders().values());
            orders.add(placement.getSortOrder());
            assertThat(orders)
                    .as("第 %d 次拖拽（prev=%s next=%s）后出现重复排序值", i + 1, prevId, nextId)
                    .doesNotHaveDuplicates();

            // 应用这次落位，把列内状态推进到下一步：
            // 真实环境里重排值会写回数据库，下一次拖拽读到的就是新的间隔。
            // 这里不需要把卡片 99 放回列表——place() 本来就会把它自己排除在外
            for (Map.Entry<Long, Integer> reorder : placement.getReorders().entrySet()) {
                columnTasks.stream()
                        .filter(t -> t.getId().equals(reorder.getKey()))
                        .forEach(t -> t.setSortOrder(reorder.getValue()));
            }
            // 列内保持有序：真实查询是 ORDER BY sort_order, id
            columnTasks.sort(Comparator.<Task>comparingInt(Task::getSortOrder)
                    .thenComparingLong(Task::getId));
        }
    }

    private static Map.Entry<Long, Integer> entry(Long key, Integer value) {
        return new LinkedHashMap<>(Map.of(key, value)).entrySet().iterator().next();
    }
}
