package com.zhangchi.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhangchi.gameasset.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 *
 * <p>模块1 的查询量很小，全部用 MyBatis-Plus 的 Lambda 条件构造器完成，
 * 不额外写 XML——条件写在 Java 里能享受编译期字段名校验，
 * 改名时不会出现「XML 里的列名拼错但编译通过」的静默失败。</p>
 *
 * @author 张忠驰
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
