package com.zhangchi.gameasset.convert;

import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.common.enums.ProjectStatus;
import com.zhangchi.gameasset.entity.Project;
import com.zhangchi.gameasset.vo.ProjectVO;
import org.springframework.beans.BeanUtils;

/**
 * 项目实体 → VO。
 *
 * @author 张忠驰
 */
public final class ProjectConverter {

    private ProjectConverter() {
    }

    /**
     * @param project 项目实体
     * @param myRole  当前登录用户在该项目中的角色，可为 null（内部调用场景）
     */
    public static ProjectVO toVO(Project project, String myRole) {
        if (project == null) {
            return null;
        }
        ProjectVO vo = new ProjectVO();
        BeanUtils.copyProperties(project, vo);
        vo.setStatusLabel(ProjectStatus.label(project.getStatus()));
        vo.setMyRole(myRole);
        // 是否可管理（前端据此显示「邀请成员」「修改」「归档」按钮）
        vo.setCanManage(ProjectRole.OWNER.equals(myRole));
        return vo;
    }
}
