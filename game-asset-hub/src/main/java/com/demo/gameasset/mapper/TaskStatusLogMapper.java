package com.demo.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.gameasset.entity.TaskStatusLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务状态变更日志 Mapper（模块3）。
 *
 * <p>没有手写 SQL：本模块只做「按 task_id 列出日志」这一个查询，
 * 条件构造器足够；模块4 的燃尽图回放 SQL 会在那时按需追加，
 * 现在写了却没有调用方，只会成为没人维护的死代码。</p>
 *
 * @author 管理员
 */
@Mapper
public interface TaskStatusLogMapper extends BaseMapper<TaskStatusLog> {
}
