package com.zhangchi.gameasset.common.constant;

/**
 * Redis key 统一收口。
 *
 * <p>key 散落在各处写字符串是缓存类 bug 的重灾区（少写一个冒号就静默失配），
 * 统一在这里拼装，并保持 <b>业务域:对象:标识</b> 的层级便于 redis-cli 排查。</p>
 *
 * @author 张忠驰
 */
public final class RedisKeys {

    /** 登出令牌黑名单前缀：{@code auth:blacklist:{jti}} */
    private static final String TOKEN_BLACKLIST_PREFIX = "gah:auth:blacklist:";

    /** 项目概览缓存前缀：{@code gah:project:overview:{projectId}} */
    private static final String PROJECT_OVERVIEW_PREFIX = "gah:project:overview:";

    /** 通知未读数前缀（模块3 接入）：{@code gah:notify:unread:{userId}} */
    private static final String NOTIFY_UNREAD_PREFIX = "gah:notify:unread:";

    /** 资产编号自增序列前缀：{@code gah:asset:no:{projectId}:{categoryCode}} */
    private static final String ASSET_NO_SEQ_PREFIX = "gah:asset:no:";

    /** 资产版本上传分布式锁前缀：{@code gah:lock:asset:upload:{assetId}} */
    private static final String ASSET_UPLOAD_LOCK_PREFIX = "gah:lock:asset:upload:";

    /** 项目统计图表的缓存前缀（模块4）：{@code gah:stat:{projectId}:{metric}} */
    private static final String PROJECT_STAT_PREFIX = "gah:stat:";

    /** 统计缓存里的三张指标，值与 {@code ProjectStatCache} 的常量一一对应 */
    public static final String STAT_METRIC_ASSET_COMPLETION = "asset-completion";

    public static final String STAT_METRIC_ASSET_CATEGORY = "asset-category";

    public static final String STAT_METRIC_WORKLOAD = "workload";

    private RedisKeys() {
    }

    /**
     * 登出令牌黑名单 key。
     * TTL 设为 token 的剩余有效期，令牌自然过期后黑名单条目也自动消失，不会无限膨胀。
     */
    public static String tokenBlacklist(String jti) {
        return TOKEN_BLACKLIST_PREFIX + jti;
    }

    /**
     * 项目概览缓存 key。
     * 项目信息 / 成员发生任何写操作时都要 delete 这个 key 主动失效。
     */
    public static String projectOverview(Long projectId) {
        return PROJECT_OVERVIEW_PREFIX + projectId;
    }

    /** 通知未读数 key（模块3 使用，此处先定义避免后续改动散落） */
    public static String notifyUnread(Long userId) {
        return NOTIFY_UNREAD_PREFIX + userId;
    }

    /**
     * 通知未读数 key（模块3：按「用户 + 项目」维度）。
     *
     * <p>未读数为什么不跨项目合计：通知列表本身按当前项目上下文过滤
     * （前端的铃铛长在项目里），若角标统计的是全部项目，
     * 就会出现「角标显示 3、点开只有 1 条」——用户不会去怀疑口径，
     * 只会觉得这个数字是错的。因此 key 与列表用同一个维度。</p>
     *
     * <p>{@code projectId} 为空时退化成 {@link #notifyUnread(Long)}，
     * 用于「与项目无关」的场景，保证两处前缀始终一致。</p>
     */
    public static String notifyUnread(Long userId, Long projectId) {
        return projectId == null ? notifyUnread(userId) : NOTIFY_UNREAD_PREFIX + userId + ":" + projectId;
    }

    /**
     * 资产编号自增序列 key（模块2）。
     *
     * <p>按「项目 + 分类编码」维度自增，而不是一个项目一个序列：
     * 资产编号是 {@code {分类code}_{序号}}，若共用一个序列，
     * {@code CHR_SM_0001} 与 {@code TEX_0007} 之间的空洞会让团队误以为丢过资产。</p>
     *
     * <p><b>为什么用 Redis INCR 而不是 {@code MAX(asset_no)+1}</b>：
     * 后者是「先查后写」，两个人同时新建资产必然拿到同一个号，
     * 只能靠唯一约束失败后重试；INCR 是原子的，正常路径下一次成功。
     * 唯一约束仍然保留，作为 Redis 不可用或序列被清空时的兜底。</p>
     */
    public static String assetNoSeq(Long projectId, String categoryCode) {
        return ASSET_NO_SEQ_PREFIX + projectId + ":" + categoryCode;
    }

    /**
     * 资产版本上传的分布式锁 key（模块2，文档 5.2 的
     * {@code lock:asset:upload:{assetId}}）。
     *
     * <p>锁的粒度刻意取到<b>单个资产</b>而不是整个项目：
     * 同一项目里不同资产的版本上传互不影响，锁冲突概率低；
     * 而同一资产的并发上传（同一个美术连点两次、或两个人同时补版本）
     * 正是版本号会重复的场景，必须串行。</p>
     */
    public static String assetUploadLock(Long assetId) {
        return ASSET_UPLOAD_LOCK_PREFIX + assetId;
    }

    /**
     * 项目统计图表的缓存 key（模块4）。
     *
     * <p><b>前缀 {@code gah:stat:{projectId}:}</b>，重建数据库的脚本按这个前缀清理
     * （见 README 的「缓存 key」一节）。</p>
     *
     * <p><b>只缓存「项目维度」的三张图</b>（资产完成率 / 分类分布 / 成员工作量）：
     * 它们的入参只有 {@code projectId}，同一项目下谁来看都是同一份数据。</p>
     *
     * <p>燃尽图与任务趋势<b>刻意不进缓存</b>：它们的横轴含「今天」，
     * 缓存一天就会让今天的曲线还是昨天画的那个形状——用户会把它当成 bug，
     * 而且这种「数据看着对、就是不动」的现象极难排查。
     * 宁可多查一次数据库，也不缓存含当前日期的曲线。</p>
     *
     * @param projectId 项目ID
     * @param metric    指标名，取 {@link #STAT_METRIC_ASSET_COMPLETION}
     *                  / {@link #STAT_METRIC_ASSET_CATEGORY} / {@link #STAT_METRIC_WORKLOAD}
     */
    public static String projectStat(Long projectId, String metric) {
        return PROJECT_STAT_PREFIX + projectId + ":" + metric;
    }

    /**
     * 某个项目全部统计缓存的<b>前缀</b>，供「按前缀清理」使用。
     *
     * <p>本机 Redis 是 Windows 版 5.0.14.1，{@code --scan --pattern} 静默返回空，
     * 清键只能用 {@code KEYS}（见 PROGRESS 第六节），所以这里给出前缀拼接规则，
     * 让清理脚本与业务代码用的是同一份约定，而不是脚本里另写一串字面量。</p>
     */
    public static String projectStatPrefix(Long projectId) {
        return PROJECT_STAT_PREFIX + projectId + ":";
    }
}
