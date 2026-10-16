package com.zhangchi.gameasset.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhangchi.gameasset.common.constant.RedisKeys;
import com.zhangchi.gameasset.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 项目统计图表的缓存（模块4）。
 *
 * <p>与 {@code ProjectOverviewCache} 的分工完全对称：把 key 拼装、TTL、失效
 * 三件事收在一个类里，让「某条写路径忘了失效」这种最难复现的 bug
 * 在代码评审里一眼可见。</p>
 *
 * <h3>缓存什么、不缓存什么</h3>
 * <ul>
 *   <li><b>缓存</b>：资产完成率与状态分布、分类分布、成员工作量。
 *       这三张图只依赖 {@code projectId}，同一项目下谁来看都是同一份数据，
 *       可以按<b>项目维度</b>共享。</li>
 *   <li><b>不缓存</b>：燃尽图、任务趋势。它们的横轴与「今天」绑定，
 *       缓存哪怕一天，用户第二天打开看到的还是昨天那条曲线——
 *       这种「数字对但不动」的现象会被直接当成 bug 报上来。
 *       相关接口每次直查数据库（几十毫秒的聚合，值得）。</li>
 * </ul>
 *
 * <h3>为什么这里绝不放任何用户维度字段</h3>
 * <p>模块1 的概览缓存踩过一次：{@code myTasks}（当前登录用户的待办）
 * 被放进了按项目共享的缓存，结果 A 的待办串给了 B 看。
 * 本类缓存的三个 VO 里的每一个字段都是「项目维度」的——
 * 成员工作量的成员列表也是「项目里的所有成员」，与调用者是谁无关。
 * 将来若要往里加「我的贡献」这类字段，必须先把它拆到缓存之外，
 * 不能顺手挂进 VO。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectStatCache {

    /** 三张图表数据的有效期，与项目概览保持一致（10 分钟） */
    public static final Duration TTL = Duration.ofMinutes(10);

    private final RedisCacheService redisCacheService;

    /** 读缓存，未命中返回 null（Redis 不可用时同样返回 null，退化为直查） */
    public <T> T get(Long projectId, String metric, Class<T> type) {
        if (projectId == null) {
            return null;
        }
        return redisCacheService.get(RedisKeys.projectStat(projectId, metric), type);
    }

    /**
     * 读<strong>集合类型</strong>的缓存。
     *
     * <p>必须用 {@link TypeReference} 而不是 {@code Class}：泛型在运行期被擦除，
     * 传 {@code List.class} 只能拿到 {@code List<LinkedHashMap>}，
     * 取元素时抛 {@code ClassCastException}——而且是在读了缓存之后才抛，
     * 表现为「第一次请求正常、第二次 500」，极易误判成并发问题。
     * 分类分布图返回的就是一个 List，走这条重载。</p>
     */
    public <T> T get(Long projectId, String metric, TypeReference<T> type) {
        if (projectId == null) {
            return null;
        }
        return redisCacheService.get(RedisKeys.projectStat(projectId, metric), type);
    }

    /** 写缓存 */
    public void put(Long projectId, String metric, Object value) {
        if (projectId == null) {
            return;
        }
        redisCacheService.set(RedisKeys.projectStat(projectId, metric), value, TTL);
    }

    /**
     * 失效某个项目的全部统计缓存。
     *
     * <p>三个 key 一起删，而不是「哪个变了删哪个」：这三张图的数据源
     * （任务 / 资产 / 成员）本来就高度交叠——拖一次任务既影响工作量也影响
     * 资产完成率的口径，逐个判断「这次写操作该删哪几个 key」迟早会漏。
     * 键只有三个，全删的代价可以忽略。</p>
     *
     * <p><b>调用方不需要直接调它</b>：所有既有的写路径都会走
     * {@code ProjectOverviewCache#evict}，那里已经串上了本方法（见该方法的注释）。
     * 只有「不影响概览公共指标、却影响工作量」的写操作
     * （任务的指派变更）需要单独调一次。</p>
     */
    public void evict(Long projectId) {
        if (projectId == null) {
            return;
        }
        redisCacheService.delete(RedisKeys.projectStat(projectId, RedisKeys.STAT_METRIC_ASSET_COMPLETION));
        redisCacheService.delete(RedisKeys.projectStat(projectId, RedisKeys.STAT_METRIC_ASSET_CATEGORY));
        redisCacheService.delete(RedisKeys.projectStat(projectId, RedisKeys.STAT_METRIC_WORKLOAD));
        log.debug("[统计缓存] 已失效 projectId={}", projectId);
    }
}
