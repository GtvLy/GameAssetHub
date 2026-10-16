package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.common.constant.RedisKeys;
import com.zhangchi.gameasset.service.RedisCacheService;
import com.zhangchi.gameasset.vo.ProjectOverviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 项目概览缓存。
 *
 * <p>把 key 拼装、TTL、失效三件事收在同一个类里，
 * 是因为「缓存 + 写操作主动失效」最容易出的 bug 是
 * <b>某条写路径忘了失效</b>——那会让用户看到过期数据且极难复现。
 * 让所有写路径都调用同一个 {@link #evict(Long)}，
 * 遗漏时在代码评审里一眼可见。</p>
 *
 * <p>TTL 取 10 分钟（文档 5.9）：概览是聚合查询，但数据变化频率不高，
 * 10 分钟内即使有写操作也会被 evict 掉，不存在「等 TTL 自然过期」的窗口。</p>
 *
 * <h3>模块2 / 模块3 增量：写入前补齐资产与任务指标</h3>
 *
 * <p>模块1 阶段 {@code ProjectOverviewVO} 的 {@code asset*} 与 {@code task*} 系列
 * 都是占位 0，因为 {@code asset} / {@code task} 两张表分别属于模块2 与模块3。
 * 建表之后，这些指标在 {@link #put} 里由 {@link AssetOverviewSupport} 与
 * {@link TaskOverviewSupport} 真正填上。</p>
 *
 * <p><b>为什么接在这里而不是 {@code ProjectServiceImpl#overview}</b>：
 * {@code ProjectServiceImpl} 构造 VO、调用 {@code put}、然后把<b>同一个对象</b>
 * 作为响应返回。接在 {@code put} 里意味着「进缓存的」与「返回给前端的」
 * 必然是同一份数据；若接在 Service 里，就得保证「先填后 put」的顺序不错，
 * 一旦顺序写反，表现是「首次请求指标全是 0、刷新一下才对」——
 * 这种依赖调用顺序的正确性很难在评审时看出来。放在这里，顺序由方法体固定。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectOverviewCache {

    /** 缓存有效期 */
    public static final Duration TTL = Duration.ofMinutes(10);

    private final RedisCacheService redisCacheService;

    /** 资产指标聚合（模块2 提供，见类注释） */
    private final AssetOverviewSupport assetOverviewSupport;

    /** 任务指标聚合（模块3 提供，补齐 taskTotal / taskDone / taskOverdue） */
    private final TaskOverviewSupport taskOverviewSupport;

    /**
     * 统计图表缓存（模块4 提供）。见 {@link #evict(Long)} 的注释：
     * 它是被本类<b>连带失效</b>的，不是本类的数据依赖。
     */
    private final ProjectStatCache projectStatCache;

    /** 读缓存，未命中返回 null */
    public ProjectOverviewVO get(Long projectId) {
        return redisCacheService.get(RedisKeys.projectOverview(projectId), ProjectOverviewVO.class);
    }

    /**
     * 写缓存。
     *
     * <p>写入前补齐模块2 的资产指标（总数 / 已通过 / 待审核 / 完成率 / 最近资产）
     * 与模块3 的项目维度任务指标（总数 / 已完成 / 已逾期），
     * 这是原地修改 {@code overview}，调用方拿到的对象与缓存里的完全一致。</p>
     *
     * <p><b>只有项目维度的指标能进这里</b>。{@code myTasks} 是用户维度数据，
     * 由 {@code TaskOverviewSupport#withMyTasks} 在缓存之外单独挂，
     * 放进本方法就会把 A 的待办缓存给 B 看到。</p>
     */
    public void put(Long projectId, ProjectOverviewVO overview) {
        if (projectId != null && overview != null) {
            assetOverviewSupport.fill(overview, projectId);
            taskOverviewSupport.fill(overview, projectId);
        }
        redisCacheService.set(RedisKeys.projectOverview(projectId), overview, TTL);
    }

    /**
     * 主动失效。
     * <p>以下写操作必须调用：项目信息修改、项目状态变更、项目删除、
     * 成员邀请、成员角色变更、成员移除、凭码加入；
     * <b>模块2 补充</b>：资产新建 / 修改 / 删除、版本上传、当前版本切换、
     * 状态流转 —— 这些都会改变概览上的资产指标。</p>
     *
     * <h3>模块4 补充：这里连带失效统计图表缓存</h3>
     * <p>统计数据（资产完成率 / 分类分布 / 成员工作量）的数据源与概览指标
     * 完全是同一批写操作——拖一次任务、传一个版本、加一个成员，
     * 概览与统计图都会变。既然所有写路径<b>已经</b>都调用了
     * {@link #evict(Long)}，把统计缓存的失效挂在这一条通道上，
     * 就等于是「一次修改、一处失效」，不给将来的新写路径留下
     * 「只失效了概览、统计图还是旧的」这种半失效的可能。</p>
     *
     * <p>耦合成这样是刻意的取舍：反过来让每个写路径显式调两次
     * （{@code overviewCache.evict()} + {@code statCache.evict()}），
     * 漏掉任何一处都会产生「概览对了、统计图是旧的」的静默 bug，
     * 而那种 bug 只能靠人肉比对两个页面的数字才能发现。</p>
     *
     * <p>唯一不被这条通道覆盖的是
     * {@code TaskServiceImpl#assign}（改指派人只影响工作量、不影响概览的
     * 公共指标），它在自己的方法体里单独调了一次统计缓存失效。</p>
     */
    public void evict(Long projectId) {
        if (projectId == null) {
            return;
        }
        redisCacheService.delete(RedisKeys.projectOverview(projectId));
        projectStatCache.evict(projectId);
        log.debug("[概览缓存] 已失效 projectId={}（含统计图表缓存）", projectId);
    }
}
