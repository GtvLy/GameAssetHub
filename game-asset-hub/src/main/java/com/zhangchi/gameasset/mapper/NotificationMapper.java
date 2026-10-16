package com.zhangchi.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhangchi.gameasset.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知 Mapper（模块3）。
 *
 * <p>没有手写 SQL：列表分页、未读数、标记已读三种查询用条件构造器表达
 * 已经足够清晰，而它们的过滤条件（user_id / is_read / project_id）
 * 恰恰是最不该被手写字符串绕过去的部分。</p>
 *
 * @author 张忠驰
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
