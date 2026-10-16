package com.zhangchi.gameasset.common.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 资产状态与状态流转白名单（文档 3.2 DDL 注释 + 5.3 状态流转规则）。
 *
 * <pre>
 * IN_PROGRESS ──提交审核──&gt; PENDING ──通过──&gt; APPROVED ──废弃──&gt; DEPRECATED
 *                              │                  │
 *                              └──打回──&gt; REJECTED │
 *                                            │     └──退回继续改──&gt; IN_PROGRESS
 *                                            ├──重新提交──&gt; PENDING
 *                                            └──放弃──&gt; IN_PROGRESS / DEPRECATED
 * </pre>
 *
 * <p><b>为什么把流转表放在枚举里</b>：文档 6 节「状态流转可配置」的要求是
 * 「调整工作流只改一处」。如果流转判断散在 Service 的 if-else 里，
 * 加一个状态或改一条边就要满仓库找判断点，漏掉一处就是一个能绕过审核的洞。
 * 这里集中定义，Service 只调用 {@link #canTransfer(String, String)}，
 * 单元测试也可以逐条比对白名单的每一格。</p>
 *
 * <p><b>默认拒绝</b>：未知状态、null、自身到自身一律返回 false，
 * 不存在「拿不准就放行」的分支。</p>
 *
 * @author 张忠驰
 */
public enum AssetStatus {

    /** 制作中：新建资产的初始状态，也是上传新版本后的回退状态 */
    IN_PROGRESS("制作中"),

    /** 待审核：已提交，等待 OWNER 审核 */
    PENDING("待审核"),

    /** 已通过：审核通过，可作为正式资源使用 */
    APPROVED("已通过"),

    /** 已打回：审核未通过，必须带审核意见 */
    REJECTED("已打回"),

    /** 已废弃：不再使用，但历史版本仍可追溯 */
    DEPRECATED("已废弃");

    /** 中文名称，用于前端标签与错误提示 */
    private final String label;

    /**
     * 状态流转白名单（文档 5.3 的 {@code STATUS_FLOW}）。
     *
     * <p>逐条对应文档里的箭头：</p>
     * <ul>
     *   <li>{@code IN_PROGRESS → PENDING, DEPRECATED}</li>
     *   <li>{@code PENDING → APPROVED, REJECTED}</li>
     *   <li>{@code REJECTED → PENDING, IN_PROGRESS, DEPRECATED}</li>
     *   <li>{@code APPROVED → DEPRECATED, IN_PROGRESS}（已通过的资产可退回继续修改）</li>
     *   <li>{@code DEPRECATED → IN_PROGRESS}（废弃的资产可以重新启用）</li>
     * </ul>
     *
     * <p>用 {@link EnumMap} 而不是 {@code Map.of}：需要遍历全部状态构造，
     * 且 EnumMap 的键序与枚举声明序一致，便于前端按稳定顺序展示可选项。</p>
     */
    private static final Map<AssetStatus, Set<AssetStatus>> FLOW;

    static {
        Map<AssetStatus, Set<AssetStatus>> flow = new EnumMap<>(AssetStatus.class);
        flow.put(IN_PROGRESS, nextOf(PENDING, DEPRECATED));
        flow.put(PENDING, nextOf(APPROVED, REJECTED));
        flow.put(APPROVED, nextOf(DEPRECATED, IN_PROGRESS));
        flow.put(REJECTED, nextOf(PENDING, IN_PROGRESS, DEPRECATED));
        flow.put(DEPRECATED, nextOf(IN_PROGRESS));
        FLOW = Collections.unmodifiableMap(flow);
    }

    AssetStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 构造不可变的后继状态集合，<b>保持传入顺序</b>。
     *
     * <p>刻意不用 {@link EnumSet}：它按<b>枚举声明顺序</b>迭代，
     * 会把 {@code REJECTED} 的后继排成 {@code IN_PROGRESS, PENDING, DEPRECATED}，
     * 与文档 5.3 画出的 {@code PENDING / IN_PROGRESS / DEPRECATED} 不一致。
     * 顺序会直接决定前端「提交审核 / 退回继续改 / 废弃」三个按钮的排列，
     * 依据文档顺序比依据枚举写法顺序更不容易出错。</p>
     */
    private static Set<AssetStatus> nextOf(AssetStatus first, AssetStatus... rest) {
        Set<AssetStatus> set = new LinkedHashSet<>();
        set.add(first);
        set.addAll(Arrays.asList(rest));
        return Collections.unmodifiableSet(set);
    }

    // ==================== 流转判定 ====================

    /**
     * 判断一次状态流转是否被白名单允许。
     *
     * @param from 变更前状态（字符串，来自数据库）
     * @param to   变更后状态
     * @return 允许返回 true；任一参数为 null、非法、状态未变化、不在白名单内，一律 false
     */
    public static boolean canTransfer(String from, String to) {
        AssetStatus source = of(from);
        AssetStatus target = of(to);
        if (source == null || target == null) {
            return false;
        }
        // 状态未变化不算流转：放行会让「重复点两次提交审核」写出两条无意义的历史记录
        if (source == target) {
            return false;
        }
        return FLOW.getOrDefault(source, Collections.emptySet()).contains(target);
    }

    /**
     * 某个状态的全部合法后继状态（字符串集合），供前端渲染「可执行的操作」按钮。
     * 未知状态返回空集合。
     */
    public static Set<String> nextStatuses(String from) {
        AssetStatus source = of(from);
        if (source == null) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (AssetStatus status : FLOW.getOrDefault(source, Collections.emptySet())) {
            result.add(status.name());
        }
        return Collections.unmodifiableSet(result);
    }

    /** 字符串 → 枚举，非法值返回 null（不抛异常，交给调用方决定返回 400 还是放行判定） */
    public static AssetStatus of(String status) {
        if (status == null) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        for (AssetStatus value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    /** 状态是否合法（用于 DTO 校验，避免把任意字符串写进 asset.status） */
    public static boolean isValid(String status) {
        return of(status) != null;
    }

    /** 中文标签；未知状态原样返回，便于排查脏数据 */
    public static String label(String status) {
        AssetStatus value = of(status);
        return value == null ? String.valueOf(status) : value.getLabel();
    }

    /** 全部合法状态名，拼错误提示用 */
    public static Set<String> all() {
        Set<String> result = new LinkedHashSet<>();
        for (AssetStatus value : values()) {
            result.add(value.name());
        }
        return Collections.unmodifiableSet(result);
    }
}
