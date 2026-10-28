package com.demo.gameasset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.gameasset.entity.ProjectMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目成员 Mapper。
 *
 * <p>RBAC 校验的每一次查询都必须带上 {@code project_id}
 * （文档 5.1 第二层「查询 SQL 强制带 project_id」），
 * 严禁「先按主键查出来再在 Java 里判断归属」——那种写法一旦漏判就是越权。</p>
 *
 * @author 管理员
 */
@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {
}
