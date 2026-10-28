package com.demo.gameasset.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 让 MyBatis-Plus 的 Lambda 条件构造器脱离 Spring 也能工作。
 *
 * <p><b>为什么需要这个东西</b>：{@code LambdaQueryWrapper} 把
 * {@code ProjectMember::getProjectId} 解析成列名 {@code project_id}，
 * 靠的是一份「实体 → 表结构」的元数据缓存（{@code TableInfo}）。
 * 这份缓存在正常运行时由 {@code MybatisPlusAutoConfiguration} 扫描实体时建立；
 * 而单元测试刻意不加载 Spring 上下文，缓存是空的，一旦构造 Lambda 条件就会抛：</p>
 * <pre>
 * MybatisPlusException: can not find lambda cache for this entity
 * </pre>
 *
 * <p>这里手工把 {@code TableInfo} 注册进去，等价于启动时自动完成的那一步。
 * 这样测试既能覆盖「真实的 Lambda 条件生成的 SQL 是否带 project_id」，
 * 又不需要起 Spring、连 MySQL。</p>
 *
 * <p>注意：这个类只存在于 {@code src/test}，不进生产产物。</p>
 *
 * @author 管理员
 */
public final class MybatisPlusTestSupport {

    /** 已注册的实体，避免重复初始化 */
    private static final Set<Class<?>> INITIALIZED = ConcurrentHashMap.newKeySet();

    private MybatisPlusTestSupport() {
    }

    /**
     * 注册实体的表结构元数据（幂等）。
     *
     * @param entityClasses 需要参与 Lambda 条件构造的实体类
     */
    public static void initTableInfo(Class<?>... entityClasses) {
        if (entityClasses == null || entityClasses.length == 0) {
            return;
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entityClass : entityClasses) {
            if (entityClass == null || !INITIALIZED.add(entityClass)) {
                continue;
            }
            TableInfoHelper.initTableInfo(assistant, entityClass);
        }
    }

    /** 注册本项目模块1 的三张表，测试里最常用的一次性调用 */
    public static void initModuleOneEntities() {
        initTableInfo(
                com.demo.gameasset.entity.SysUser.class,
                com.demo.gameasset.entity.Project.class,
                com.demo.gameasset.entity.ProjectMember.class);
    }

    /**
     * 注册模块2 的四张资产表（外加模块1 的 SysUser / ProjectMember，
     * 因为资产服务要批量查上传人昵称，权限闸门也要回查成员表）。
     */
    public static void initModuleTwoEntities() {
        initTableInfo(
                com.demo.gameasset.entity.Asset.class,
                com.demo.gameasset.entity.AssetCategory.class,
                com.demo.gameasset.entity.AssetVersion.class,
                com.demo.gameasset.entity.AssetStatusLog.class,
                com.demo.gameasset.entity.SysUser.class,
                com.demo.gameasset.entity.ProjectMember.class);
    }

    /**
     * 注册模块3 的四张表（外加模块1/2 里会一起参与条件构造的几张表）。
     *
     * <p>任务装配要批量查用户、资产、分类与版本，任务概览要查历史脏数据下
     * 的重复排序值，因此模块1/2 的实体也一并注册——漏注册的表现是
     * 「can not find lambda cache for this entity」，而那个报错信息
     * 不会告诉你是哪一行条件构造触发的。</p>
     */
    public static void initModuleThreeEntities() {
        initTableInfo(
                com.demo.gameasset.entity.Task.class,
                com.demo.gameasset.entity.TaskComment.class,
                com.demo.gameasset.entity.TaskStatusLog.class,
                com.demo.gameasset.entity.Notification.class,
                com.demo.gameasset.entity.SysUser.class,
                com.demo.gameasset.entity.ProjectMember.class,
                com.demo.gameasset.entity.Asset.class,
                com.demo.gameasset.entity.AssetCategory.class,
                com.demo.gameasset.entity.AssetVersion.class);
    }

    /**
     * 注册模块4 新增的 {@code project_report}，外加统计服务会读到的模块1 实体
     * （项目主表提供 {@code start_date} / {@code plan_end_date}）。
     *
     * <p>统计服务自己不构造 Lambda 条件（聚合全在手写 SQL 里），
     * 但报表服务要回查项目、任务概览支持类要用 Lambda 统计任务，
     * 因此这几张表必须一起注册——漏注册的表现是
     * 「can not find lambda cache for this entity」，而且不会告诉你是哪一行。</p>
     */
    public static void initModuleFourEntities() {
        initTableInfo(
                com.demo.gameasset.entity.ProjectReport.class,
                com.demo.gameasset.entity.Project.class,
                com.demo.gameasset.entity.Task.class,
                com.demo.gameasset.entity.TaskStatusLog.class,
                com.demo.gameasset.entity.ProjectMember.class,
                com.demo.gameasset.entity.SysUser.class);
    }
}
