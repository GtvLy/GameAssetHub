package com.demo.gameasset.service;

import com.demo.gameasset.dto.ProjectCreateDTO;
import com.demo.gameasset.dto.ProjectUpdateDTO;
import com.demo.gameasset.vo.ProjectOverviewVO;
import com.demo.gameasset.vo.ProjectVO;

import java.util.List;

/**
 * 项目服务。
 *
 * @author 管理员
 */
public interface ProjectService {

    /** 我参与的项目列表（仅 ACTIVE 成员关系），含我在每个项目中的角色 */
    List<ProjectVO> listMyProjects();

    /** 创建项目，创建者自动成为 OWNER 并写入一条 project_member 记录 */
    ProjectVO create(ProjectCreateDTO dto);

    /** 项目详情（调用方须为该项目成员） */
    ProjectVO detail(Long projectId);

    /** 修改项目信息（OWNER） */
    ProjectVO update(Long projectId, ProjectUpdateDTO dto);

    /** 变更项目状态：ACTIVE / PAUSED / ARCHIVED（OWNER） */
    ProjectVO changeStatus(Long projectId, String status);

    /** 归档项目（OWNER，逻辑删除） */
    void archive(Long projectId);

    /** 项目概览聚合数据，走 Redis 缓存（TTL 10 分钟） */
    ProjectOverviewVO overview(Long projectId);
}
