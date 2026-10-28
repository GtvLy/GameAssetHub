package com.demo.gameasset.service.support;

import com.demo.gameasset.entity.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 看板拖拽的落位算法（文档 3.3 第 3 点）。
 *
 * <p><b>核心约定</b>：{@code sort_order} 是列内排序的唯一依据，取大间隔整数
 * （每次 +1000），拖拽时取前后卡片排序值的中间值——正常路径下<b>一条 UPDATE</b>
 * 就能完成落位，不必重排整列。只有相邻间距小于 {@link #MIN_GAP} 时
 * （同一处反复拖拽若干次之后）才对该列做一次整体重排，把间隔恢复成 1000。</p>
 *
 * <p><b>本类为什么是纯静态的</b>：这是模块3 最容易出错的一段——
 * 算错的表现是「两张卡片排序值相同」，而看板仍能打开，只是刷新一下顺序
 * 可能变、并且再也拖不回正确位置。这种 bug 在真机上一眼看不出来，
 * 必须能被单元测试逐格覆盖。因此这里不碰数据库、不碰 Spring，
 * 只吃一个「目标列现有任务列表」+「客户端给的前后卡片ID」，吐出落位结果。</p>
 *
 * <p><b>三条不变量</b>（单元测试逐条盯住）：</p>
 * <ol>
 *   <li>落位值与目标列<b>已有任务</b>的排序值都不相等；</li>
 *   <li>{@code prev} / {@code next} 指向的卡片若已被他人移动、不在目标列、
 *       或干脆不存在，<b>不报错</b>——重新按当前列内的相邻关系给出合理位置；</li>
 *   <li>左右相邻的判定以「当前列内的实际顺序」为准，而不是客户端传来的ID对：
 *       客户端可能传来一对<b>不相邻</b>的卡片（中间还夹着别人刚拖进去的卡片），
 *       直接取两者中点会正好压在中间那张卡片上，产生重复排序值。</li>
 * </ol>
 *
 * <p><b>已知边界</b>：两个人<b>同时</b>把不同的卡片拖到同一个位置（例如都拖到队首），
 * 两次请求各自读到的是同一份列内数据，算出的排序值可能相同。
 * 本类只能保证「基于已读到的列内数据不产生重复」；跨事务的并发重复由
 * 稳定的次排序键（查询一律 {@code ORDER BY sort_order ASC, id ASC}）兜住，
 * 而该位置上任何一次后续拖拽都会触发重排，把它修正回来。
 * 要彻底消除需要给 (project_id, column_status, sort_order) 加唯一约束，
 * 但那样整列重排过程中会与唯一约束互斥（重排中途必然出现瞬时重复），
 * 代价大于收益。</p>
 *
 * @author 管理员
 */
public final class TaskSortOrderCalculator {

    /** 排序步长：新卡片排在队尾时 {@code last + STEP}，整列重排后间隔也恢复成它 */
    public static final int STEP = 1000;

    /** 相邻间距小于它就重排整列。取 10 而不是 2：2 只保证「这一次」不重复， */
    /** 10 能让同一处的连续拖拽多撑几次再触发重排 */
    public static final int MIN_GAP = 10;

    private TaskSortOrderCalculator() {
    }

    /**
     * 计算落位。
     *
     * @param columnTasks  目标列<b>当前</b>的任务（调用方应从数据库现查，
     *                     可包含也可不包含被移动的任务，本方法会剔除它）；
     *                     顺序无所谓，本方法内部会按「列内顺序」重排
     * @param movingTaskId 被移动的任务ID
     * @param prevTaskId   客户端给的「前一张卡片」ID，可空（表示拖到队首或列尾）
     * @param nextTaskId   客户端给的「后一张卡片」ID，可空
     * @return 落位结果：自身的排序值 + 需要连带更新的其他任务
     */
    public static Placement place(List<Task> columnTasks,
                                  Long movingTaskId,
                                  Long prevTaskId,
                                  Long nextTaskId) {
        List<Task> ordered = inColumnOrder(columnTasks, movingTaskId);
        int size = ordered.size();

        int insertIndex = resolveInsertIndex(ordered, prevTaskId, nextTaskId);
        Task prev = insertIndex > 0 ? ordered.get(insertIndex - 1) : null;
        Task next = insertIndex < size ? ordered.get(insertIndex) : null;

        int sortOrder;
        boolean rebalance = false;

        if (prev == null && next == null) {
            // 目标列是空的：这是它唯一的一张卡片
            sortOrder = STEP;
        } else if (prev == null) {
            // 队首：排在当前第一张之前，留出一个步长
            sortOrder = order(next) - STEP;
        } else if (next == null) {
            // 队尾：排在当前最后一张之后
            sortOrder = order(prev) + STEP;
        } else {
            int gap = order(next) - order(prev);
            if (gap < MIN_GAP) {
                // 中间塞不下了（反复拖拽已把间隔磨平）：整列重排
                rebalance = true;
                sortOrder = 0;
            } else {
                sortOrder = order(prev) + gap / 2;
            }
        }

        // 兜底：算出来的值若与列内已有任务撞车，改用整列重排。
        // 理论上队首/队尾/中间三条路径都不会撞（新值严格在区间外或区间内），
        // 但库里若因为历史脏数据或手工 SQL 存在重复值，这一步会顺手把列治好，
        // 而不是把重复继续传下去。
        if (!rebalance && isTaken(ordered, sortOrder)) {
            rebalance = true;
            sortOrder = 0;
        }

        if (!rebalance) {
            return new Placement(sortOrder, Collections.emptyMap());
        }
        return rebalance(ordered, movingTaskId, insertIndex);
    }

    /**
     * 按「列内顺序」整理任务列表：剔除被移动的任务，再按
     * {@code sort_order ASC, id ASC} 排序。
     *
     * <p>{@code id} 是并列时的稳定次排序键，与查询 SQL 的
     * {@code ORDER BY sort_order ASC, id ASC} 保持一致——两处不一致的话，
     * 「算法算出来的相邻关系」与「前端看到的相邻关系」会对不上。</p>
     */
    public static List<Task> inColumnOrder(List<Task> columnTasks, Long movingTaskId) {
        List<Task> ordered = new ArrayList<>();
        if (columnTasks != null) {
            for (Task task : columnTasks) {
                if (task == null || task.getId() == null) {
                    continue;
                }
                if (movingTaskId != null && movingTaskId.equals(task.getId())) {
                    continue;
                }
                ordered.add(task);
            }
        }
        ordered.sort(Comparator
                .comparingInt((Task t) -> order(t))
                .thenComparing(Task::getId));
        return ordered;
    }

    /**
     * 求插入下标。
     *
     * <p>优先认 {@code prevTaskId}（插在它后面），其次认 {@code nextTaskId}
     * （插在它前面）。两者都认不出来（不存在、属于别的列、已被删除）时
     * 退到列尾——「拖到不认识的位置」按追加处理，比报错更贴近用户预期，
     * 也不会因为别人的并发操作让这次拖拽直接失败。</p>
     */
    private static int resolveInsertIndex(List<Task> ordered, Long prevTaskId, Long nextTaskId) {
        int prevIndex = indexOf(ordered, prevTaskId);
        if (prevIndex >= 0) {
            return prevIndex + 1;
        }
        int nextIndex = indexOf(ordered, nextTaskId);
        if (nextIndex >= 0) {
            return nextIndex;
        }
        return ordered.size();
    }

    private static int indexOf(List<Task> ordered, Long taskId) {
        if (taskId == null) {
            return -1;
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (taskId.equals(ordered.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTaken(List<Task> ordered, int sortOrder) {
        for (Task task : ordered) {
            if (order(task) == sortOrder) {
                return true;
            }
        }
        return false;
    }

    /**
     * 整列重排：把「被移动的任务」插到 {@code insertIndex} 处，
     * 然后自 1 开始按 {@link #STEP} 递增重新编号。
     *
     * <p>重排值从 {@code 1 * STEP} 算起而不是从 0：0 是 DDL 的默认值，
     * 新插入的行在拿到排序值之前就是 0，从 1 起可以让「还没排过序的行」
     * 与「已排序的行」天然分开。</p>
     */
    private static Placement rebalance(List<Task> ordered, Long movingTaskId, int insertIndex) {
        List<Long> sequence = new ArrayList<>(ordered.size() + 1);
        for (int i = 0; i <= ordered.size(); i++) {
            if (i == insertIndex) {
                sequence.add(movingTaskId);
            }
            if (i < ordered.size()) {
                sequence.add(ordered.get(i).getId());
            }
        }

        Map<Long, Integer> reorders = new LinkedHashMap<>();
        int movingOrder = 0;
        for (int i = 0; i < sequence.size(); i++) {
            Long id = sequence.get(i);
            int value = (i + 1) * STEP;
            if (Objects.equals(id, movingTaskId)) {
                movingOrder = value;
            } else {
                reorders.put(id, value);
            }
        }
        return new Placement(movingOrder, reorders);
    }

    /** 排序值，null 视为 0（DDL 是 NOT NULL DEFAULT 0，这里只是防御脏数据） */
    private static int order(Task task) {
        return task.getSortOrder() == null ? 0 : task.getSortOrder();
    }

    /** 落位结果 */
    public static final class Placement {

        /** 被移动任务最终的排序值——接口要把它原样返回给前端 */
        private final int sortOrder;

        /** 需要连带更新的其他任务：任务ID → 新排序值；未发生重排时为空 */
        private final Map<Long, Integer> reorders;

        Placement(int sortOrder, Map<Long, Integer> reorders) {
            this.sortOrder = sortOrder;
            this.reorders = reorders;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public Map<Long, Integer> getReorders() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(reorders));
        }

        /** 是否触发了整列重排 */
        public boolean isRebalanced() {
            return !reorders.isEmpty();
        }
    }
}
