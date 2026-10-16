package com.zhangchi.gameasset.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhangchi.gameasset.common.enums.AssetStatus;
import com.zhangchi.gameasset.common.enums.NotificationType;
import com.zhangchi.gameasset.common.enums.ProjectMemberStatus;
import com.zhangchi.gameasset.common.enums.ProjectRole;
import com.zhangchi.gameasset.common.enums.TaskColumn;
import com.zhangchi.gameasset.entity.Asset;
import com.zhangchi.gameasset.entity.ProjectMember;
import com.zhangchi.gameasset.entity.Task;
import com.zhangchi.gameasset.mapper.ProjectMemberMapper;
import com.zhangchi.gameasset.mapper.TaskMapper;
import com.zhangchi.gameasset.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资产状态流转产生的通知（模块3 为模块2 补上的联动，文档 5.3 + 5.5 + 5.8）。
 *
 * <p>文档 5.8 的触发事件表里有三行属于资产侧：</p>
 * <pre>
 * 资产审核通过或打回 → 资产负责人      ASSET_REVIEW
 * 资产提交审核       → 项目 OWNER      ASSET_REVIEW
 * （5.5 联动）资产提交审核 → 该资产下未完成任务的负责人 ASSET_REVIEW
 * </pre>
 *
 * <p><b>为什么单独抽成组件而不是写进 {@code AssetServiceImpl}</b>：
 * 资产服务已经承担了版本、状态机、存储三件事，再塞进「通知谁」的编排会很难读；
 * 而且这段逻辑完全靠「资产 + 项目成员 + 关联任务」三类数据就能决定，
 * 与资产服务其余部分没有耦合，独立出来可以单独测。</p>
 *
 * @author 张忠驰
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetReviewNotifier {

    private final ProjectMemberMapper projectMemberMapper;
    private final TaskMapper taskMapper;
    private final NotificationService notificationService;

    /**
     * 资产状态变更后推送对应通知。
     *
     * @param asset    变更后的资产（status 已是新值）
     * @param remark   审核意见，可为空
     */
    public void afterStatusChange(Asset asset, String remark) {
        if (asset == null) {
            return;
        }
        String to = asset.getStatus();
        Long projectId = asset.getProjectId();

        if (AssetStatus.PENDING.name().equals(to)) {
            notifyOwnersOnSubmit(asset, projectId, remark);
            notifyRelatedTaskAssignees(asset, projectId);
            return;
        }
        if (AssetStatus.APPROVED.name().equals(to) || AssetStatus.REJECTED.name().equals(to)) {
            notifyAssetOwner(asset, projectId, to, remark);
        }
        // 其余流转（退回制作中、废弃）不产生通知：它们不要求任何人立刻做决定，
        // 发了只会稀释真正需要响应的那几条
    }

    /** 提交审核 → 项目 OWNER（文档 5.3 第 5 步）。提交人自己就是 OWNER 时由 push 挡掉 */
    private void notifyOwnersOnSubmit(Asset asset, Long projectId, String remark) {
        if (projectId == null) {
            return;
        }
        List<ProjectMember> owners = projectMemberMapper.selectList(
                Wrappers.<ProjectMember>lambdaQuery()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getRole, ProjectRole.OWNER)
                        .eq(ProjectMember::getStatus, ProjectMemberStatus.ACTIVE));
        for (ProjectMember owner : owners) {
            notificationService.push(NotificationType.ASSET_REVIEW, projectId, owner.getUserId(),
                    "资产提交审核：" + asset.getName(),
                    remark == null ? "请安排审核" : remark,
                    NotificationType.NotificationBizType.ASSET, asset.getId());
        }
    }

    /**
     * 提交审核 → 该资产下所有未完成任务的负责人（文档 5.5 的联动提示）。
     *
     * <p>{@code column_status != 'DONE'} 这个条件是有意义的：已经完成的任务
     * 不需要再「进入验收」，给它的负责人推一条只会有噪音。</p>
     */
    private void notifyRelatedTaskAssignees(Asset asset, Long projectId) {
        if (projectId == null) {
            return;
        }
        List<Task> related = taskMapper.selectList(
                Wrappers.<Task>lambdaQuery()
                        .eq(Task::getProjectId, projectId)
                        .eq(Task::getAssetId, asset.getId())
                        .ne(Task::getColumnStatus, TaskColumn.FINISHED.name()));
        for (Task task : related) {
            notificationService.push(NotificationType.ASSET_REVIEW, projectId, task.getAssigneeId(),
                    "关联资产已提交审核：" + asset.getName(),
                    "可在任务「" + task.getTitle() + "」中进行验收",
                    NotificationType.NotificationBizType.TASK, task.getId());
        }
        if (!related.isEmpty()) {
            log.debug("[资产] 提交审核联动通知 assetId={} 相关任务 {} 个", asset.getId(), related.size());
        }
    }

    /** 通过 / 打回 → 资产负责人（文档 5.3 第 5 步） */
    private void notifyAssetOwner(Asset asset, Long projectId, String to, String remark) {
        boolean approved = AssetStatus.APPROVED.name().equals(to);
        notificationService.push(NotificationType.ASSET_REVIEW, projectId, asset.getOwnerId(),
                (approved ? "资产审核通过：" : "资产被打回：") + asset.getName(),
                remark,
                NotificationType.NotificationBizType.ASSET, asset.getId());
    }
}
