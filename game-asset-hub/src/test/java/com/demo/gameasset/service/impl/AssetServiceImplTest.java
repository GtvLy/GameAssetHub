package com.demo.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.gameasset.common.enums.AssetStatus;
import com.demo.gameasset.common.enums.ProjectRole;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.common.util.Md5Util;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.AssetCreateDTO;
import com.demo.gameasset.dto.AssetQueryDTO;
import com.demo.gameasset.dto.AssetStatusDTO;
import com.demo.gameasset.dto.AssetUpdateDTO;
import com.demo.gameasset.dto.Md5CheckDTO;
import com.demo.gameasset.dto.UploadVersionDTO;
import com.demo.gameasset.entity.Asset;
import com.demo.gameasset.entity.AssetCategory;
import com.demo.gameasset.entity.AssetStatusLog;
import com.demo.gameasset.entity.AssetVersion;
import com.demo.gameasset.mapper.AssetCategoryMapper;
import com.demo.gameasset.mapper.AssetMapper;
import com.demo.gameasset.mapper.AssetStatusLogMapper;
import com.demo.gameasset.mapper.AssetVersionMapper;
import com.demo.gameasset.mapper.SysUserMapper;
import com.demo.gameasset.service.ChunkUploadService;
import com.demo.gameasset.service.TaskService;
import com.demo.gameasset.service.support.AssetActionGuard;
import com.demo.gameasset.service.support.AssetNoGenerator;
import com.demo.gameasset.service.support.AssetReviewNotifier;
import com.demo.gameasset.service.support.AssetVersionLock;
import com.demo.gameasset.service.support.ProjectAccessGuard;
import com.demo.gameasset.service.support.ProjectOverviewCache;
import com.demo.gameasset.storage.StorageService;
import com.demo.gameasset.support.MybatisPlusTestSupport;
import com.demo.gameasset.vo.AssetVO;
import com.demo.gameasset.vo.AssetVersionVO;
import com.demo.gameasset.vo.Md5CheckVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产服务测试（模块2 的核心逻辑）。
 *
 * <p>覆盖文档 9 节点名的测试要点：状态流转白名单、打回必须填意见、
 * 版本号自增、{@code is_current} 切换、MD5 秒传、资产编号生成、
 * 单资源查询 SQL 带 {@code project_id}、越权 403。</p>
 *
 * <p>全部 Mockito mock 掉 Mapper 与外部依赖，<b>不加载 Spring、
 * 不连 MySQL、不连 Redis</b>。分布式锁用放行式桩替代（锁本身的行为
 * 由 {@code AssetVersionLockTest} 覆盖），权限闸门用真实实现 +
 * 真实的 {@code RolePermissionMap}，这样「ARTIST 不能审核」这条
 * 测的是真正的权限矩阵，而不是我桩出来的结论。</p>
 *
 * @author 管理员
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetServiceImpl 资产与版本")
class AssetServiceImplTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long OTHER_PROJECT_ID = 99L;
    private static final Long ASSET_ID = 128L;
    private static final Long USER_ID = 1L;
    private static final Long CATEGORY_ID = 3L;
    private static final String CATEGORY_CODE = "PROP";

    /** 一份确定的文件内容，用来算真实的 MD5 */
    private static final byte[] FILE_CONTENT = "SM_Crate_A 的二进制内容".getBytes(StandardCharsets.UTF_8);
    private static final String FILE_MD5 = Md5Util.md5Hex(FILE_CONTENT);

    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetCategoryMapper categoryMapper;
    @Mock
    private AssetVersionMapper versionMapper;
    @Mock
    private AssetStatusLogMapper statusLogMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private StorageService storageService;
    @Mock
    private ChunkUploadService chunkUploadService;
    @Mock
    private ProjectAccessGuard projectAccessGuard;
    @Mock
    private AssetNoGenerator assetNoGenerator;
    @Mock
    private AssetVersionLock versionLock;
    @Mock
    private ProjectOverviewCache overviewCache;
    /** 模块3 接入：状态流转的通知编排（该组件自身由 AssetReviewNotifierTest 覆盖） */
    @Mock
    private AssetReviewNotifier reviewNotifier;
    /** 模块3 接入：资产详情里的「相关任务」（由 TaskServiceImplTest 覆盖） */
    @Mock
    private TaskService taskService;

    private AssetServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisPlusTestSupport.initModuleTwoEntities();
        AssetActionGuard actionGuard = new AssetActionGuard(projectAccessGuard);
        service = new AssetServiceImpl(assetMapper, categoryMapper, versionMapper, statusLogMapper,
                sysUserMapper, storageService, chunkUploadService, actionGuard,
                assetNoGenerator, versionLock, overviewCache, reviewNotifier, taskService);

        UserContext.setUserId(USER_ID);
        UserContext.setUsername("demo");

        // 分布式锁放行：单元测试关心的是「锁保护下做了什么」，
        // 而不是抢锁本身（那个由 AssetVersionLockTest 单独覆盖）
        Mockito.doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(versionLock).execute(anyLong(), any());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 测试夹具 ====================

    private Asset asset(String status) {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setProjectId(PROJECT_ID);
        asset.setCategoryId(CATEGORY_ID);
        asset.setAssetNo("PROP_0001");
        asset.setName("木箱 A 型");
        asset.setStatus(status);
        asset.setPriority("P1");
        asset.setVersionCount(1);
        asset.setOwnerId(USER_ID);
        asset.setCurrentVersionId(101L);
        asset.setDeleted(0);
        return asset;
    }

    private AssetCategory category() {
        AssetCategory category = new AssetCategory();
        category.setId(CATEGORY_ID);
        category.setProjectId(PROJECT_ID);
        category.setName("场景道具");
        category.setCode(CATEGORY_CODE);
        category.setColor("#67c23a");
        return category;
    }

    private AssetVersion version(Long id, int versionNo, String fileKey, String md5, int isCurrent) {
        AssetVersion version = new AssetVersion();
        version.setId(id);
        version.setAssetId(ASSET_ID);
        version.setProjectId(PROJECT_ID);
        version.setVersionNo(versionNo);
        version.setVersionName("V" + versionNo);
        version.setFileName("SM_Crate_A.fbx");
        version.setFileKey(fileKey);
        version.setFileSize((long) FILE_CONTENT.length);
        version.setFileExt("fbx");
        version.setFileMd5(md5);
        version.setUploaderId(USER_ID);
        version.setIsCurrent(isCurrent);
        return version;
    }

    private AssetStatusDTO statusDto(String toStatus, String remark) {
        AssetStatusDTO dto = new AssetStatusDTO();
        dto.setToStatus(toStatus);
        dto.setRemark(remark);
        return dto;
    }

    private UploadVersionDTO uploadMeta() {
        UploadVersionDTO meta = new UploadVersionDTO();
        meta.setVersionName("优化 UV");
        meta.setChangeLog("优化 UV 排布");
        meta.setPolyCount(41250);
        return meta;
    }

    private MultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", FILE_CONTENT);
    }

    /** 让 assetMapper.insert 回填自增主键，模拟真实数据库行为 */
    private void stubAssetInsertBackfillsId() {
        Mockito.doAnswer(invocation -> {
            Asset inserted = invocation.getArgument(0);
            inserted.setId(ASSET_ID);
            return 1;
        }).when(assetMapper).insert(any(Asset.class));
    }

    private void stubVersionInsertBackfillsId(Long versionId) {
        Mockito.doAnswer(invocation -> {
            AssetVersion inserted = invocation.getArgument(0);
            inserted.setId(versionId);
            return 1;
        }).when(versionMapper).insert(any(AssetVersion.class));
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Asset> captureAssetUpdate() {
        ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(assetMapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<Asset>) captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Wrapper<Asset> captureAssetQuery() {
        ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(assetMapper).selectOne(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private AssetVersion captureInsertedVersion() {
        ArgumentCaptor<AssetVersion> captor = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(captor.capture());
        return captor.getValue();
    }

    private AssetStatusLog captureStatusLog() {
        ArgumentCaptor<AssetStatusLog> captor = ArgumentCaptor.forClass(AssetStatusLog.class);
        verify(statusLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================================================================
    // 一、单资源查询必须带 project_id（模块1 建立的第二层防护）
    // ==================================================================

    @Nested
    @DisplayName("数据隔离：单资源查询 SQL 强制带 project_id")
    class ProjectScope {

        @Test
        @DisplayName("资产详情：查询条件同时包含 id 与 project_id")
        void detailQueriesWithProjectId() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));

            service.detail(ASSET_ID, PROJECT_ID);

            String sql = captureAssetQuery().getTargetSql();
            assertThat(sql).as("实际 SQL 片段：%s", sql).contains("project_id");
            assertThat(sql).as("实际 SQL 片段：%s", sql).contains("id");
        }

        @Test
        @DisplayName("资产列表：分页查询条件里第一个就是 project_id")
        void pageQueriesWithProjectId() {
            when(assetMapper.selectPage(any(), any())).thenReturn(new Page<>());

            service.page(PROJECT_ID, new AssetQueryDTO());

            ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(assetMapper).selectPage(any(), captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("project_id");
        }

        @Test
        @DisplayName("资产不存在 → 404；资产存在但属于别的项目 → 403（不让 404 掩盖越权）")
        void distinguishesForbiddenFromNotFound() {
            when(assetMapper.selectOne(any())).thenReturn(null);
            when(assetMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.detail(ASSET_ID, OTHER_PROJECT_ID))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403))
                    .hasMessageContaining("不属于当前项目");

            when(assetMapper.selectCount(any())).thenReturn(0L);
            assertThatThrownBy(() -> service.detail(ASSET_ID, OTHER_PROJECT_ID))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
        }

        @Test
        @DisplayName("项目上下文缺失时直接报错，不让 null 变成全表查询")
        void rejectsNullProjectId() {
            assertThatThrownBy(() -> service.detail(ASSET_ID, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("项目上下文缺失");
        }

        @Test
        @DisplayName("版本查询同时带 project_id 与 asset_id，越权同样返回 403")
        void versionQueriesAreScoped() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectOne(any())).thenReturn(null);
            when(versionMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.setCurrentVersion(ASSET_ID, 999L, PROJECT_ID))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));

            ArgumentCaptor<Wrapper<AssetVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(versionMapper).selectOne(captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("project_id");
        }

        @Test
        @DisplayName("新建资产时分类必须是本项目的")
        void categoryMustBelongToProject() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(categoryMapper.selectOne(any())).thenReturn(null);

            AssetCreateDTO dto = new AssetCreateDTO();
            dto.setCategoryId(999L);
            dto.setName("偷渡的资产");

            assertThatThrownBy(() -> service.create(PROJECT_ID, dto, file("a.fbx"), null, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("分类不存在或不属于当前项目");

            ArgumentCaptor<Wrapper<AssetCategory>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(categoryMapper).selectOne(captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("project_id");
            verify(assetMapper, never()).insert(any(Asset.class));
        }
    }

    // ==================================================================
    // 二、状态流转白名单（文档 5.3）
    // ==================================================================

    @Nested
    @DisplayName("状态流转：白名单、权限、审核意见留痕")
    class StatusFlow {

        @Test
        @DisplayName("IN_PROGRESS → PENDING：ARTIST 可以提交审核")
        void artistCanSubmitForReview() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));

            AssetVO vo = service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("PENDING", "首版完成，提交审核"));

            assertThat(vo.getStatus()).isEqualTo("PENDING");
            assertThat(vo.getStatusLabel()).isEqualTo("待审核");
            assertThat(vo.getNextStatuses()).containsExactly("APPROVED", "REJECTED");
            verify(overviewCache).evict(PROJECT_ID);
        }

        /**
         * 模块3 接入通知编排时真机验证抓到的缺陷：编排拿到的资产必须是
         * <b>变更后</b>的状态。
         *
         * <p>原实现把「同步内存实体」放在编排之后，于是提交审核
         * （REJECTED → PENDING）会被编排当成「打回」，给美术推一条
         * 「资产被打回」——状态是对的、通知是反的，而单独测
         * {@code AssetReviewNotifier} 时完全看不出来（它拿到的入参就是对的），
         * 只有把两个组件串起来跑才会暴露。</p>
         */
        @Test
        @DisplayName("状态流转的通知编排拿到的是变更后的资产，不是变更前的")
        void notifierSeesTheUpdatedStatus() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.REJECTED.name()));

            service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("PENDING", "重新混音完成，提交审核"));

            ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
            verify(reviewNotifier).afterStatusChange(captor.capture(), any());
            assertThat(captor.getValue().getStatus())
                    .as("拿变更前的 REJECTED 去编排，会把「提交审核」通知成「被打回」")
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("审核通过：编排拿到的也是变更后的状态")
        void notifierSeesApprovedStatus() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));

            service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("APPROVED", "准予通过"));

            ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
            verify(reviewNotifier).afterStatusChange(captor.capture(), any());
            assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
            assertThat(captor.getValue().getReviewRemark()).isEqualTo("准予通过");
        }

        @Test
        @DisplayName("APPROVED → PENDING 被拒绝（文档 9 节点名的用例：审核通过不能被绕过）")
        void approvedCannotGoBackToPending() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));

            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("PENDING", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不允许的状态流转");

            verify(assetMapper, never()).update(isNull(), any());
            verify(statusLogMapper, never()).insert(any(AssetStatusLog.class));
        }

        @Test
        @DisplayName("IN_PROGRESS → APPROVED 被拒绝：不能跳过审核直接通过")
        void cannotSkipReview() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));

            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("APPROVED", "直接通过")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不允许的状态流转");
        }

        @Test
        @DisplayName("ARTIST 不能执行通过/打回/废弃（缺少 ASSET_REVIEW）")
        void artistCannotReview() {
            UserContext.setProjectRole(ProjectRole.ARTIST);

            // 每个目标状态都从一个「流转合法、但权限不够」的前态出发，
            // 这样失败原因只可能是权限（403），而不是流转被白名单拦掉（400）。
            // PENDING → APPROVED / REJECTED 合法；APPROVED → DEPRECATED 合法
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));
            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("APPROVED", "同意")))
                    .as("ARTIST 不能通过审核")
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));

            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("REJECTED", "打回")))
                    .as("ARTIST 不能打回")
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));

            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("DEPRECATED", "废弃")))
                    .as("ARTIST 不能废弃")
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));

            verify(assetMapper, never()).update(isNull(), any());
        }

        @Test
        @DisplayName("OWNER 可以通过审核，并写入审核人 / 审核时间 / 审核意见")
        void ownerCanApprove() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));

            AssetVO vo = service.changeStatus(ASSET_ID, PROJECT_ID,
                    statusDto("APPROVED", "模型结构合理，UV 需在下一版本优化"));

            assertThat(vo.getStatus()).isEqualTo("APPROVED");
            assertThat(vo.getReviewerId()).isEqualTo(USER_ID);
            assertThat(vo.getReviewTime()).isNotNull();
            assertThat(vo.getReviewRemark()).isEqualTo("模型结构合理，UV 需在下一版本优化");

            AssetStatusLog log = captureStatusLog();
            assertThat(log.getFromStatus()).isEqualTo("PENDING");
            assertThat(log.getToStatus()).isEqualTo("APPROVED");
            assertThat(log.getOperatorId()).isEqualTo(USER_ID);
            assertThat(log.getRemark()).isEqualTo("模型结构合理，UV 需在下一版本优化");
        }

        @Test
        @DisplayName("打回（REJECTED）时审核意见不得为空 —— 空串与纯空白都不行")
        void rejectRequiresRemark() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));

            for (String blank : new String[]{null, "", "   "}) {
                assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("REJECTED", blank)))
                        .as("打回意见为 [%s] 时必须拒绝", blank)
                        .isInstanceOf(BizException.class)
                        .hasMessageContaining("必须填写审核意见");
            }
            verify(assetMapper, never()).update(isNull(), any());
            verify(statusLogMapper, never()).insert(any(AssetStatusLog.class));
        }

        @Test
        @DisplayName("打回时必须留下审核意见（有意见则通过并写进流转历史）")
        void rejectWithRemarkSucceeds() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));

            AssetVO vo = service.changeStatus(ASSET_ID, PROJECT_ID,
                    statusDto("REJECTED", "尾段有爆音，请重新混音"));

            assertThat(vo.getStatus()).isEqualTo("REJECTED");
            assertThat(vo.getNextStatuses()).containsExactly("PENDING", "IN_PROGRESS", "DEPRECATED");
            assertThat(captureStatusLog().getRemark()).isEqualTo("尾段有爆音，请重新混音");
        }

        @Test
        @DisplayName("APPROVED → IN_PROGRESS（退回继续改）：ARTIST 有 ASSET_WRITE，允许")
        void approvedCanReturnToInProgress() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));

            AssetVO vo = service.changeStatus(ASSET_ID, PROJECT_ID,
                    statusDto("IN_PROGRESS", "发现贴图错位，退回继续修改"));

            assertThat(vo.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(captureStatusLog().getFromStatus()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("DEPRECATED → IN_PROGRESS（重新启用）")
        void deprecatedCanBeReactivated() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.DEPRECATED.name()));

            assertThat(service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("IN_PROGRESS", "重新启用")).getStatus())
                    .isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("非法的目标状态值 → 400，且不写库")
        void rejectsUnknownTargetStatus() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));

            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("FINISHED", null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("非法的资产状态");

            verify(statusLogMapper, never()).insert(any(AssetStatusLog.class));
        }

        @Test
        @DisplayName("状态未变化（PENDING → PENDING）被拒绝，避免写出重复的审核历史")
        void rejectsSelfTransition() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));

            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("PENDING", "重复提交")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不允许的状态流转");
        }

        @Test
        @DisplayName("每一次流转都恰好写一条 asset_status_log（可追溯）")
        void writesExactlyOneStatusLogPerTransition() {
            UserContext.setProjectRole(ProjectRole.OWNER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));

            service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("APPROVED", "通过"));

            verify(statusLogMapper, Mockito.times(1)).insert(any(AssetStatusLog.class));
            // 状态更新的 SQL 也必须带 project_id
            assertThat(captureAssetUpdate().getTargetSql()).contains("project_id");
        }
    }

    // ==================================================================
    // 三、版本号自增与 is_current 切换（文档 5.2 / 3.3）
    // ==================================================================

    @Nested
    @DisplayName("版本上传：版本号自增、is_current 切换、状态回退")
    class VersionUpload {

        private void stubHappyPath(String assetStatus, int currentMaxVersion) {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(assetStatus));
            when(versionMapper.selectMaxVersionNo(ASSET_ID, PROJECT_ID)).thenReturn(currentMaxVersion);
            when(storageService.upload(any(MultipartFile.class), anyString())).thenReturn("7/128/new.fbx");
            stubVersionInsertBackfillsId(999L);
        }

        @Test
        @DisplayName("版本号在资产维度自增：已有 V3 时新版本是 V4")
        void versionNoIncrementsFromMax() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 3);

            AssetVersionVO vo = service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("SM_Crate_A.fbx"));

            assertThat(vo.getVersionNo()).isEqualTo(4);
            assertThat(captureInsertedVersion().getVersionNo()).isEqualTo(4);
            verify(versionMapper).selectMaxVersionNo(ASSET_ID, PROJECT_ID);
        }

        @Test
        @DisplayName("首个版本是 V1：最大版本号为 0 时 +1")
        void firstVersionIsOne() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 0);

            assertThat(service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx")).getVersionNo())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("is_current 切换：先 clearCurrent 再插入新版本，顺序不可颠倒")
        void clearsCurrentBeforeInsertingNew() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 1);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            InOrder inOrder = Mockito.inOrder(versionMapper, assetMapper);
            inOrder.verify(versionMapper).clearCurrent(ASSET_ID, PROJECT_ID);
            inOrder.verify(versionMapper).insert(any(AssetVersion.class));
            inOrder.verify(assetMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("新版本标记 is_current = 1，保证同资产只有一条当前版本")
        void newVersionIsMarkedCurrent() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 1);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            assertThat(captureInsertedVersion().getIsCurrent()).isEqualTo(1);
            verify(versionMapper, Mockito.times(1)).clearCurrent(ASSET_ID, PROJECT_ID);
        }

        @Test
        @DisplayName("上传新版本后主表指向新版本、版本数更新为最大版本号")
        void updatesAssetCurrentVersionAndCount() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 3);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            LambdaUpdateWrapper<Asset> update = captureAssetUpdate();
            assertThat(update.getTargetSql()).contains("project_id");
            assertThat(update.getSqlSet()).contains("current_version_id");
            assertThat(update.getSqlSet()).contains("version_count");
        }

        @Test
        @DisplayName("上传新版本后状态回退为 IN_PROGRESS，并补写一条状态流转记录")
        void rollsStatusBackToInProgress() {
            stubHappyPath(AssetStatus.APPROVED.name(), 2);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));

            AssetVersionVO vo = service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            assertThat(vo.getVersionNo()).isEqualTo(3);
            LambdaUpdateWrapper<Asset> update = captureAssetUpdate();
            assertThat(update.getSqlSet()).contains("status");
            // 回退也要留痕，否则「昨天还是已通过，今天怎么变回制作中」查不到原因
            AssetStatusLog log = captureStatusLog();
            assertThat(log.getFromStatus()).isEqualTo("APPROVED");
            assertThat(log.getToStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("已经处于 IN_PROGRESS 时不重复写流转日志（无变化的流转不该留痕）")
        void doesNotLogStatusWhenAlreadyInProgress() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 1);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            verify(statusLogMapper, never()).insert(any(AssetStatusLog.class));
        }

        @Test
        @DisplayName("文件名重命名为 UUID + 原扩展名，原始文件名只进数据库")
        void storesFileUnderUuidNameAndKeepsOriginalName() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 1);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("SM_Crate_A.fbx"));

            AssetVersion inserted = captureInsertedVersion();
            assertThat(inserted.getFileName()).as("原始文件名存库").isEqualTo("SM_Crate_A.fbx");
            assertThat(inserted.getFileExt()).isEqualTo("fbx");
            assertThat(inserted.getFileKey()).as("落盘路径由存储服务生成").isEqualTo("7/128/new.fbx");
            // 目录按 projectId/assetId 组织
            verify(storageService).upload(any(MultipartFile.class), eq(PROJECT_ID + "/" + ASSET_ID));
        }

        @Test
        @DisplayName("版本元数据（备注名 / 面数 / 变更说明）完整落库")
        void persistsVersionMetadata() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 1);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            AssetVersion inserted = captureInsertedVersion();
            assertThat(inserted.getVersionName()).isEqualTo("优化 UV");
            assertThat(inserted.getPolyCount()).isEqualTo(41250);
            assertThat(inserted.getChangeLog()).isEqualTo("优化 UV 排布");
            assertThat(inserted.getUploaderId()).isEqualTo(USER_ID);
            assertThat(inserted.getProjectId()).isEqualTo(PROJECT_ID);
        }

        @Test
        @DisplayName("未填版本备注名时默认 V{版本号}")
        void defaultsVersionName() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 4);

            service.uploadVersion(ASSET_ID, PROJECT_ID, new UploadVersionDTO(), file("a.fbx"));

            assertThat(captureInsertedVersion().getVersionName()).isEqualTo("V5");
        }

        @Test
        @DisplayName("上传成功后主动失效项目概览缓存")
        void evictsOverviewCache() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 1);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            verify(overviewCache).evict(PROJECT_ID);
        }

        @Test
        @DisplayName("上传新版本不删除任何历史文件（版本不可变）")
        void neverDeletesHistoricalFiles() {
            stubHappyPath(AssetStatus.IN_PROGRESS.name(), 1);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            verify(storageService, never()).delete(anyString());
        }
    }

    // ==================================================================
    // 四、MD5 秒传（文档 3.3 第 6 点 / 5.2）
    // ==================================================================

    @Nested
    @DisplayName("MD5 秒传")
    class Md5InstantUpload {

        @Test
        @DisplayName("命中已有 MD5：复用 file_key，跳过物理上传")
        void reusesFileKeyWhenMd5Exists() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));
            // 历史版本里已经有同一份文件（MD5 真实一致）
            when(versionMapper.selectOne(any()))
                    .thenReturn(version(77L, 1, "7/33/old.fbx", FILE_MD5, 0));
            when(versionMapper.selectMaxVersionNo(ASSET_ID, PROJECT_ID)).thenReturn(1);
            stubVersionInsertBackfillsId(999L);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("同一份文件.fbx"));

            verify(storageService, never()).upload(any(MultipartFile.class), anyString());
            AssetVersion inserted = captureInsertedVersion();
            assertThat(inserted.getFileKey()).as("复用已有物理文件").isEqualTo("7/33/old.fbx");
            assertThat(inserted.getFileMd5()).isEqualTo(FILE_MD5);
        }

        @Test
        @DisplayName("未命中 MD5：正常落盘，file_key 用新写入的地址")
        void uploadsPhysicallyWhenMd5IsNew() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));
            when(versionMapper.selectOne(any())).thenReturn(null);
            when(versionMapper.selectMaxVersionNo(ASSET_ID, PROJECT_ID)).thenReturn(1);
            when(storageService.upload(any(MultipartFile.class), anyString())).thenReturn("7/128/fresh.fbx");
            stubVersionInsertBackfillsId(999L);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("新文件.fbx"));

            verify(storageService).upload(any(MultipartFile.class), eq(PROJECT_ID + "/" + ASSET_ID));
            assertThat(captureInsertedVersion().getFileKey()).isEqualTo("7/128/fresh.fbx");
        }

        @Test
        @DisplayName("服务端计算出的 MD5 会被记录，供后续秒传判定")
        void recordsComputedMd5() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));
            when(versionMapper.selectOne(any())).thenReturn(null);
            when(versionMapper.selectMaxVersionNo(ASSET_ID, PROJECT_ID)).thenReturn(0);
            when(storageService.upload(any(MultipartFile.class), anyString())).thenReturn("k");
            stubVersionInsertBackfillsId(1L);

            service.uploadVersion(ASSET_ID, PROJECT_ID, uploadMeta(), file("a.fbx"));

            assertThat(captureInsertedVersion().getFileMd5()).isEqualTo(FILE_MD5);
        }

        @Test
        @DisplayName("客户端声明的 MD5 与服务端算出的不一致 → 400，不写库")
        void rejectsMd5Mismatch() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));

            UploadVersionDTO meta = uploadMeta();
            meta.setFileMd5("00000000000000000000000000000000");

            assertThatThrownBy(() -> service.uploadVersion(ASSET_ID, PROJECT_ID, meta, file("a.fbx")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("MD5 校验失败");

            verify(versionMapper, never()).insert(any(AssetVersion.class));
        }

        @Test
        @DisplayName("不带文件且未给 MD5 → 400")
        void requiresFileOrMd5() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));

            assertThatThrownBy(() -> service.uploadVersion(ASSET_ID, PROJECT_ID, new UploadVersionDTO(), null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("请上传文件");
        }

        @Test
        @DisplayName("不带文件时：MD5 在本项目内存在则秒传建版本，不落盘")
        void createsVersionFromKnownMd5WithoutFile() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));
            when(versionMapper.selectOne(any())).thenReturn(version(55L, 2, "7/33/shared.fbx", FILE_MD5, 1));
            when(versionMapper.selectMaxVersionNo(ASSET_ID, PROJECT_ID)).thenReturn(2);
            stubVersionInsertBackfillsId(999L);

            UploadVersionDTO meta = uploadMeta();
            meta.setFileMd5(FILE_MD5);
            meta.setFileName("复用上一版的文件.fbx");

            AssetVersionVO vo = service.uploadVersion(ASSET_ID, PROJECT_ID, meta, null);

            assertThat(vo.getVersionNo()).isEqualTo(3);
            verify(storageService, never()).upload(any(MultipartFile.class), anyString());
            AssetVersion inserted = captureInsertedVersion();
            assertThat(inserted.getFileKey()).isEqualTo("7/33/shared.fbx");
            assertThat(inserted.getFileName()).isEqualTo("复用上一版的文件.fbx");
        }

        @Test
        @DisplayName("不带文件且 MD5 在本项目内不存在 → 400，不允许凭空建版本")
        void rejectsUnknownMd5WithoutFile() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));
            when(versionMapper.selectOne(any())).thenReturn(null);

            UploadVersionDTO meta = uploadMeta();
            meta.setFileMd5(FILE_MD5);

            assertThatThrownBy(() -> service.uploadVersion(ASSET_ID, PROJECT_ID, meta, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无法秒传");
        }

        @Test
        @DisplayName("check-md5：命中本项目已有文件时返回资产与版本信息")
        void checkMd5HitsWithinProject() {
            when(versionMapper.selectOne(any())).thenReturn(version(77L, 3, "7/33/old.fbx", FILE_MD5, 1));
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));

            Md5CheckDTO dto = new Md5CheckDTO();
            dto.setMd5(FILE_MD5.toUpperCase());
            Md5CheckVO vo = service.checkMd5(PROJECT_ID, dto);

            assertThat(vo.getExists()).isTrue();
            assertThat(vo.getAssetId()).isEqualTo(ASSET_ID);
            assertThat(vo.getAssetName()).isEqualTo("木箱 A 型");
            assertThat(vo.getVersionId()).isEqualTo(77L);
            assertThat(vo.getMessage()).contains("命中秒传");
        }

        @Test
        @DisplayName("check-md5：未命中时 exists = false，并提示需要上传")
        void checkMd5Misses() {
            when(versionMapper.selectOne(any())).thenReturn(null);

            Md5CheckDTO dto = new Md5CheckDTO();
            dto.setMd5("abcdefabcdefabcdefabcdefabcdefab");
            Md5CheckVO vo = service.checkMd5(PROJECT_ID, dto);

            assertThat(vo.getExists()).isFalse();
            assertThat(vo.getAssetId()).isNull();
            assertThat(vo.getMessage()).contains("未命中秒传");
        }

        @Test
        @DisplayName("check-md5 的查询条件限定在本项目内（跨项目不暴露资产信息）")
        void checkMd5IsProjectScoped() {
            when(versionMapper.selectOne(any())).thenReturn(null);

            Md5CheckDTO dto = new Md5CheckDTO();
            dto.setMd5(FILE_MD5);
            service.checkMd5(PROJECT_ID, dto);

            ArgumentCaptor<Wrapper<AssetVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(versionMapper).selectOne(captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("project_id");
        }
    }

    // ==================================================================
    // 五、当前版本切换（回滚）
    // ==================================================================

    @Nested
    @DisplayName("当前版本切换：只改标记位，不删历史文件")
    class SetCurrentVersion {

        @Test
        @DisplayName("切换时先清旧标记再置新标记，并更新主表 current_version_id")
        void switchesCurrentFlagInOrder() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectOne(any())).thenReturn(version(101L, 1, "7/128/v1.fbx", FILE_MD5, 0));

            AssetVersionVO vo = service.setCurrentVersion(ASSET_ID, 101L, PROJECT_ID);

            assertThat(vo.getVersionNo()).isEqualTo(1);
            InOrder inOrder = Mockito.inOrder(versionMapper, assetMapper);
            inOrder.verify(versionMapper).clearCurrent(ASSET_ID, PROJECT_ID);
            inOrder.verify(versionMapper).update(isNull(), any());
            inOrder.verify(assetMapper).update(isNull(), any());

            assertThat(captureAssetUpdate().getSqlSet()).contains("current_version_id");
        }

        @Test
        @DisplayName("回滚历史版本不改变 version_count（时间线节点数不该被改写）")
        void doesNotChangeVersionCountOnRollback() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectOne(any())).thenReturn(version(101L, 1, "7/128/v1.fbx", FILE_MD5, 0));

            service.setCurrentVersion(ASSET_ID, 101L, PROJECT_ID);

            assertThat(captureAssetUpdate().getSqlSet()).doesNotContain("version_count");
        }

        @Test
        @DisplayName("切换当前版本不删除任何文件（历史版本必须可完整追溯）")
        void neverDeletesFiles() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectOne(any())).thenReturn(version(101L, 1, "7/128/v1.fbx", FILE_MD5, 0));

            service.setCurrentVersion(ASSET_ID, 101L, PROJECT_ID);

            verify(storageService, never()).delete(anyString());
            verify(versionMapper, never()).deleteById(anyLong());
            verify(versionMapper, never()).delete(any());
        }

        @Test
        @DisplayName("目标版本已是当前版本时幂等：不写库、不失效缓存")
        void isIdempotentWhenAlreadyCurrent() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectOne(any())).thenReturn(version(101L, 1, "7/128/v1.fbx", FILE_MD5, 1));

            service.setCurrentVersion(ASSET_ID, 101L, PROJECT_ID);

            verify(versionMapper, never()).clearCurrent(anyLong(), anyLong());
            verify(assetMapper, never()).update(isNull(), any());
            verify(overviewCache, never()).evict(any());
        }

        @Test
        @DisplayName("READ_ONLY 角色（PROGRAMMER）不能切换当前版本")
        void readOnlyRoleCannotSwitch() {
            UserContext.setProjectRole(ProjectRole.PROGRAMMER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));

            assertThatThrownBy(() -> service.setCurrentVersion(ASSET_ID, 101L, PROJECT_ID))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
        }
    }

    // ==================================================================
    // 六、新建资产与资产编号
    // ==================================================================

    @Nested
    @DisplayName("新建资产与资产编号自动生成")
    class CreateAsset {

        private AssetCreateDTO createDto() {
            AssetCreateDTO dto = new AssetCreateDTO();
            dto.setCategoryId(CATEGORY_ID);
            dto.setName("木箱 A 型");
            dto.setDescription("序章场景用木质集装箱");
            dto.setPriority("P1");
            dto.setTags("场景,道具");
            dto.setVersionName("初版高模");
            dto.setChangeLog("初版高模");
            return dto;
        }

        private void stubCreateHappyPath() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(categoryMapper.selectOne(any())).thenReturn(category());
            when(assetMapper.selectCount(any())).thenReturn(0L);
            // 编号查重走的是手写 SQL（必须连逻辑删除的资产一起数），
            // 不是 Lambda 的 selectCount——后者会被 @TableLogic 过滤掉已删行
            when(assetMapper.countByAssetNoIncludeDeleted(any(), any())).thenReturn(0);
            when(assetNoGenerator.next(PROJECT_ID, CATEGORY_CODE)).thenReturn("PROP_0001");
            stubAssetInsertBackfillsId();
            when(versionMapper.selectMaxVersionNo(ASSET_ID, PROJECT_ID)).thenReturn(0);
            when(storageService.upload(any(MultipartFile.class), anyString())).thenReturn("7/128/v1.fbx");
            stubVersionInsertBackfillsId(101L);
        }

        @Test
        @DisplayName("新建资产 = 资产行 + 首个版本 + 主表指向当前版本")
        void createsAssetWithFirstVersion() {
            stubCreateHappyPath();

            AssetVO vo = service.create(PROJECT_ID, createDto(), file("SM_Crate_A.fbx"), null, null);

            assertThat(vo.getAssetNo()).isEqualTo("PROP_0001");
            assertThat(vo.getName()).isEqualTo("木箱 A 型");
            assertThat(vo.getStatus()).isEqualTo(AssetStatus.IN_PROGRESS.name());
            assertThat(vo.getStatusLabel()).isEqualTo("制作中");
            assertThat(vo.getVersionCount()).isEqualTo(1);
            assertThat(vo.getCurrentVersionId()).isEqualTo(101L);
            assertThat(vo.getOwnerId()).isEqualTo(USER_ID);

            assertThat(captureInsertedVersion().getVersionNo()).isEqualTo(1);
            assertThat(captureAssetUpdate().getSqlSet()).contains("current_version_id");
            verify(overviewCache).evict(PROJECT_ID);
        }

        @Test
        @DisplayName("资产编号来自「分类编码 + 序号」，由生成器统一产出")
        void assetNoComesFromCategoryCode() {
            stubCreateHappyPath();

            service.create(PROJECT_ID, createDto(), file("a.fbx"), null, null);

            verify(assetNoGenerator).next(PROJECT_ID, CATEGORY_CODE);
            ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
            verify(assetMapper).insert(captor.capture());
            assertThat(captor.getValue().getAssetNo()).isEqualTo("PROP_0001");
        }

        @Test
        @DisplayName("候选编号已被占用时重新分配，避免撞 uk_project_asset_no")
        void retriesWhenCandidateNoIsTaken() {
            stubCreateHappyPath();
            // 前两次候选号都已被占用，第三次才拿到可用号。
            // 「已被占用」按含逻辑删除的口径判定：删掉的资产仍占着编号
            when(assetMapper.countByAssetNoIncludeDeleted(any(), any())).thenReturn(1, 1, 0);
            when(assetNoGenerator.next(PROJECT_ID, CATEGORY_CODE))
                    .thenReturn("PROP_0001", "PROP_0002", "PROP_0003");

            service.create(PROJECT_ID, createDto(), file("a.fbx"), null, null);

            ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
            verify(assetMapper).insert(captor.capture());
            assertThat(captor.getValue().getAssetNo()).as("最终使用未被占用的编号").isEqualTo("PROP_0003");
        }

        @Test
        @DisplayName("候选编号连续冲突时抛冲突异常，而不是写入重复编号")
        void failsAfterTooManyCollisions() {
            stubCreateHappyPath();
            when(assetMapper.countByAssetNoIncludeDeleted(any(), any())).thenReturn(1);

            assertThatThrownBy(() -> service.create(PROJECT_ID, createDto(), file("a.fbx"), null, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("资产编号分配冲突");

            verify(assetMapper, never()).insert(any(Asset.class));
        }

        @Test
        @DisplayName("优先级缺省为 P2，非法优先级直接 400")
        void resolvesPriority() {
            stubCreateHappyPath();
            AssetCreateDTO dto = createDto();
            dto.setPriority(null);

            service.create(PROJECT_ID, dto, file("a.fbx"), null, null);
            ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
            verify(assetMapper).insert(captor.capture());
            assertThat(captor.getValue().getPriority()).isEqualTo("P2");

            AssetCreateDTO bad = createDto();
            bad.setPriority("P9");
            assertThatThrownBy(() -> service.create(PROJECT_ID, bad, file("a.fbx"), null, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("非法的优先级");
        }

        @Test
        @DisplayName("只读角色（VIEWER）不能新建资产")
        void viewerCannotCreate() {
            UserContext.setProjectRole(ProjectRole.VIEWER);

            assertThatThrownBy(() -> service.create(PROJECT_ID, createDto(), file("a.fbx"), null, null))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
            verify(assetMapper, never()).insert(any(Asset.class));
        }
    }

    // ==================================================================
    // 七、修改、删除、版本列表、对比、状态历史
    // ==================================================================

    @Nested
    @DisplayName("修改 / 删除 / 版本列表 / 对比 / 状态历史")
    class Maintenance {

        @Test
        @DisplayName("修改资产：未传的字段保持原值，不会被 null 覆盖")
        void updateKeepsUnsetFields() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            Asset existing = asset(AssetStatus.IN_PROGRESS.name());
            existing.setDescription("原说明");
            existing.setTags("场景");
            when(assetMapper.selectOne(any())).thenReturn(existing);

            AssetUpdateDTO dto = new AssetUpdateDTO();
            dto.setName("木箱 B 型");

            AssetVO vo = service.update(ASSET_ID, PROJECT_ID, dto);

            assertThat(vo.getName()).isEqualTo("木箱 B 型");
            assertThat(vo.getDescription()).as("未传的说明保持原值").isEqualTo("原说明");
            assertThat(vo.getTags()).isEqualTo("场景");
            assertThat(captureAssetUpdate().getTargetSql()).contains("project_id");
            verify(overviewCache).evict(PROJECT_ID);
        }

        @Test
        @DisplayName("删除资产是逻辑删除，且不触碰版本与状态日志")
        void deleteIsLogicalAndKeepsHistory() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));

            service.delete(ASSET_ID, PROJECT_ID);

            ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(assetMapper).delete(captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("project_id");
            verify(versionMapper, never()).delete(any());
            verify(statusLogMapper, never()).delete(any());
            verify(storageService, never()).delete(anyString());
            verify(overviewCache).evict(PROJECT_ID);
        }

        @Test
        @DisplayName("VIEWER 不能删除资产（缺少 ASSET_DELETE）")
        void viewerCannotDelete() {
            UserContext.setProjectRole(ProjectRole.VIEWER);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));

            assertThatThrownBy(() -> service.delete(ASSET_ID, PROJECT_ID))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
            verify(assetMapper, never()).delete(any(Wrapper.class));
        }

        @Test
        @DisplayName("版本列表按版本号倒序，且查询带 project_id")
        void versionsAreOrderedAndScoped() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectList(any())).thenReturn(List.of(
                    version(103L, 3, "k3", "m3", 1),
                    version(102L, 2, "k2", "m2", 0),
                    version(101L, 1, "k1", "m1", 0)));

            List<AssetVersionVO> versions = service.versions(ASSET_ID, PROJECT_ID);

            assertThat(versions).hasSize(3);
            assertThat(versions).extracting(AssetVersionVO::getVersionNo).containsExactly(3, 2, 1);
            assertThat(versions.get(0).getCurrent()).isTrue();
            assertThat(versions.get(1).getCurrent()).isFalse();

            ArgumentCaptor<Wrapper<AssetVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(versionMapper).selectList(captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("project_id");
        }

        @Test
        @DisplayName("版本对比：逐项差异 + 面数与体积增量")
        void compareProducesDiffs() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            AssetVersion v1 = version(101L, 1, "k1", "m1", 0);
            v1.setPolyCount(48210);
            v1.setFileSize(189743104L);
            v1.setTextureSize(null);
            AssetVersion v3 = version(103L, 3, "k3", "m3", 1);
            v3.setPolyCount(41250);
            v3.setFileSize(163577856L);
            v3.setTextureSize("4096x4096");
            when(versionMapper.selectOne(any())).thenReturn(v1).thenReturn(v3);

            var result = service.compare(ASSET_ID, PROJECT_ID, 101L, 103L);

            assertThat(result.getFrom().getVersionNo()).isEqualTo(1);
            assertThat(result.getTo().getVersionNo()).isEqualTo(3);
            assertThat(result.getPolyCountDelta()).as("面数减少了 6960").isEqualTo(41250 - 48210);
            assertThat(result.getFileSizeDelta()).isEqualTo(163577856L - 189743104L);
            assertThat(result.getFileSizeDeltaText()).startsWith("-");
            assertThat(result.getDiffs()).isNotEmpty();
            assertThat(result.getDiffs()).anySatisfy(d -> {
                assertThat(d.field()).isEqualTo("贴图尺寸");
                assertThat(d.changed()).isTrue();
            });
        }

        @Test
        @DisplayName("对比同一个版本 → 400")
        void compareRejectsSameVersion() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));

            assertThatThrownBy(() -> service.compare(ASSET_ID, PROJECT_ID, 101L, 101L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不同的版本");
        }

        @Test
        @DisplayName("状态历史按时间倒序，并带出操作人昵称")
        void statusLogsAreReturnedNewestFirst() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            AssetStatusLog first = new AssetStatusLog();
            first.setAssetId(ASSET_ID);
            first.setFromStatus("IN_PROGRESS");
            first.setToStatus("PENDING");
            first.setOperatorId(USER_ID);
            when(statusLogMapper.selectList(any())).thenReturn(List.of(first));

            var logs = service.statusLogs(ASSET_ID, PROJECT_ID);

            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getFromStatusLabel()).isEqualTo("制作中");
            assertThat(logs.get(0).getToStatusLabel()).isEqualTo("待审核");

            ArgumentCaptor<Wrapper<AssetStatusLog>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(statusLogMapper).selectList(captor.capture());
            assertThat(captor.getValue().getTargetSql()).contains("asset_id");
        }

        @Test
        @DisplayName("状态历史接口仍然先做带 project_id 的资产归属校验")
        void statusLogsStillVerifyAssetOwnership() {
            when(assetMapper.selectOne(any())).thenReturn(null);
            when(assetMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> service.statusLogs(ASSET_ID, OTHER_PROJECT_ID))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
            verify(statusLogMapper, never()).selectList(any());
        }
    }

    // ==================================================================
    // 八、下载
    // ==================================================================

    @Nested
    @DisplayName("版本下载")
    class Download {

        @Test
        @DisplayName("下载指定版本：按 file_key 取文件，返回原始文件名")
        void downloadsByFileKey() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectOne(any())).thenReturn(version(101L, 1, "7/128/v1.fbx", FILE_MD5, 0));
            var resource = new org.springframework.core.io.ByteArrayResource(FILE_CONTENT);
            when(storageService.download("7/128/v1.fbx")).thenReturn(resource);

            var file = service.download(ASSET_ID, 101L, PROJECT_ID);

            assertThat(file.fileName()).isEqualTo("SM_Crate_A.fbx");
            assertThat(file.size()).isEqualTo(FILE_CONTENT.length);
            assertThat(file.resource()).isSameAs(resource);
        }

        @Test
        @DisplayName("版本不属于该资产 → 404/403，不会读到别人的文件")
        void rejectsVersionOfAnotherAsset() {
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.APPROVED.name()));
            when(versionMapper.selectOne(any())).thenReturn(null);
            when(versionMapper.selectCount(any())).thenReturn(0L);

            assertThatThrownBy(() -> service.download(ASSET_ID, 101L, PROJECT_ID))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
            verify(storageService, never()).download(anyString());
        }
    }

    // ==================================================================
    // 九、列表筛选
    // ==================================================================

    @Nested
    @DisplayName("资产列表筛选")
    class Listing {

        @Test
        @DisplayName("关键字同时模糊匹配名称与编号，且分组不破坏 project_id 条件")
        void keywordMatchesNameAndAssetNo() {
            when(assetMapper.selectPage(any(), any())).thenReturn(new Page<>());

            AssetQueryDTO query = new AssetQueryDTO();
            query.setKeyword("木箱");
            service.page(PROJECT_ID, query);

            ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(assetMapper).selectPage(any(), captor.capture());
            String sql = captor.getValue().getTargetSql();
            assertThat(sql).contains("project_id");
            assertThat(sql).contains("name");
            assertThat(sql).contains("asset_no");
        }

        @Test
        @DisplayName("状态 / 负责人 / 优先级筛选都进 SQL")
        void filtersAreApplied() {
            when(assetMapper.selectPage(any(), any())).thenReturn(new Page<>());

            AssetQueryDTO query = new AssetQueryDTO();
            query.setStatus("approved");
            query.setOwnerId(2L);
            query.setPriority("p0");
            service.page(PROJECT_ID, query);

            ArgumentCaptor<Wrapper<Asset>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(assetMapper).selectPage(any(), captor.capture());
            String sql = captor.getValue().getTargetSql();
            assertThat(sql).contains("status").contains("owner_id").contains("priority");
        }

        @Test
        @DisplayName("分类筛选展开子分类时用 IN (一级, 子级)")
        void categoryFilterExpandsChildren() {
            when(assetMapper.selectPage(any(), any())).thenReturn(new Page<>());
            AssetCategory child = new AssetCategory();
            child.setId(4L);
            child.setProjectId(PROJECT_ID);
            child.setParentId(1L);
            when(categoryMapper.selectList(any())).thenReturn(List.of(child));

            AssetQueryDTO query = new AssetQueryDTO();
            query.setCategoryId(1L);
            service.page(PROJECT_ID, query);

            ArgumentCaptor<Wrapper<AssetCategory>> categoryCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(categoryMapper).selectList(categoryCaptor.capture());
            assertThat(categoryCaptor.getValue().getTargetSql()).contains("project_id");

            ArgumentCaptor<Wrapper<Asset>> assetCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(assetMapper).selectPage(any(), assetCaptor.capture());
            assertThat(assetCaptor.getValue().getTargetSql()).contains("category_id");
        }

        @Test
        @DisplayName("分页参数非法时回落到默认值，不把负数页码带进 SQL")
        void normalizesPagination() {
            when(assetMapper.selectPage(any(), any())).thenReturn(new Page<>());

            AssetQueryDTO query = new AssetQueryDTO();
            query.setPage(0);
            query.setSize(-5);
            var result = service.page(PROJECT_ID, query);

            assertThat(result.getPage()).isEqualTo(1L);
            assertThat(result.getSize()).isEqualTo(20L);
        }

        @Test
        @DisplayName("没有数据时返回结构完整的分页对象，前端不必判空")
        void returnsEmptyPageStructure() {
            Page<Asset> empty = new Page<>();
            empty.setRecords(List.of());
            empty.setTotal(0);
            when(assetMapper.selectPage(any(), any())).thenReturn(empty);

            var result = service.page(PROJECT_ID, null);

            assertThat(result.getRecords()).isEmpty();
            assertThat(result.getTotal()).isZero();
            assertThat(result.getHasNext()).isFalse();
        }

        @Test
        @DisplayName("查询参数为 null 时用默认条件，不抛 NPE")
        void toleratesNullQuery() {
            when(assetMapper.selectPage(any(), any())).thenReturn(new Page<>());

            assertThat(service.page(PROJECT_ID, null).getRecords()).isEmpty();
        }
    }

    // ==================================================================
    // 十、分片上传合并后的入库（复用同一条入库路径）
    // ==================================================================

    @Nested
    @DisplayName("分片上传完成后走标准版本创建流程")
    class ChunkUploadCompletion {

        @Test
        @DisplayName("合并结果入库：版本号自增、is_current 切换、清理分片目录")
        void mergedFileGoesThroughStandardFlow() throws Exception {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));
            when(versionMapper.selectMaxVersionNo(ASSET_ID, PROJECT_ID)).thenReturn(2);
            stubVersionInsertBackfillsId(999L);

            java.nio.file.Path chunkDir = java.nio.file.Files.createTempDirectory("gah-chunk-test");
            java.nio.file.Path merged = chunkDir.resolve("merged.tmp");
            java.nio.file.Files.writeString(merged, "合并后的内容");
            when(chunkUploadService.merge(anyString(), any(), any()))
                    .thenReturn(new ChunkUploadService.MergedChunkFile(
                            merged, chunkDir, "abcdefabcdefabcdefabcdefabcdefab", 100L));
            when(storageService.upload(any(java.nio.file.Path.class), anyString(), anyString()))
                    .thenReturn("7/128/merged.fbx");

            UploadVersionDTO meta = uploadMeta();
            meta.setFileName("大模型.fbx");
            AssetVersionVO vo = service.uploadVersionByChunks(
                    ASSET_ID, PROJECT_ID, meta, "abc123", 3, "abcdefabcdefabcdefabcdefabcdefab");

            assertThat(vo.getVersionNo()).isEqualTo(3);
            assertThat(captureInsertedVersion().getFileKey()).isEqualTo("7/128/merged.fbx");
            assertThat(captureInsertedVersion().getFileMd5())
                    .as("合并阶段算出的 MD5 直接复用，不重读整个文件")
                    .isEqualTo("abcdefabcdefabcdefabcdefabcdefab");
            assertThat(java.nio.file.Files.exists(chunkDir)).as("分片目录应被清理").isFalse();
            verify(chunkUploadService).merge("abc123", 3, "abcdefabcdefabcdefabcdefabcdefab");
        }

        @Test
        @DisplayName("合并失败（分片缺失）时抛业务异常，不写任何版本记录")
        void mergeFailureAbortsVersionCreation() {
            UserContext.setProjectRole(ProjectRole.ARTIST);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));
            when(chunkUploadService.merge(anyString(), any(), any()))
                    .thenThrow(BizException.badRequest("分片不完整，缺少第 2 片"));

            assertThatThrownBy(() -> service.uploadVersionByChunks(
                    ASSET_ID, PROJECT_ID, uploadMeta(), "abc123", 3, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("分片不完整");

            verify(versionMapper, never()).insert(any(AssetVersion.class));
            verify(assetMapper, never()).update(isNull(), any());
        }
    }

    // ==================================================================
    // 十一、登录态与权限闸门
    // ==================================================================

    @Nested
    @DisplayName("登录态与角色闸门")
    class Guards {

        @Test
        @DisplayName("角色不在 UserContext 里时回查成员表（非 HTTP 入口的第二道防线）")
        void fallsBackToMemberLookupWhenRoleMissing() {
            UserContext.setProjectRole(null);
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.PENDING.name()));
            // 回查成员表得到 ARTIST → 不具备 ASSET_REVIEW
            com.demo.gameasset.entity.ProjectMember member =
                    new com.demo.gameasset.entity.ProjectMember();
            member.setRole(ProjectRole.ARTIST);
            member.setStatus("ACTIVE");
            when(projectAccessGuard.requireActiveMember(PROJECT_ID, USER_ID)).thenReturn(member);

            assertThatThrownBy(() -> service.changeStatus(ASSET_ID, PROJECT_ID, statusDto("APPROVED", "通过")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(403));
        }

        @Test
        @DisplayName("未登录时任何写操作都抛 401")
        void requiresLogin() {
            UserContext.clear();
            when(assetMapper.selectOne(any())).thenReturn(asset(AssetStatus.IN_PROGRESS.name()));

            assertThatThrownBy(() -> service.create(PROJECT_ID, new AssetCreateDTO(), file("a.fbx"), null, null))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(401));
        }
    }
}
