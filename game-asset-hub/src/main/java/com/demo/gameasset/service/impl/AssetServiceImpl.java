package com.demo.gameasset.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.gameasset.common.enums.AssetPriority;
import com.demo.gameasset.common.enums.AssetStatus;
import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.exception.BizException;
import com.demo.gameasset.common.result.ResultCode;
import com.demo.gameasset.common.util.FileUtil;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.convert.AssetConverter;
import com.demo.gameasset.convert.AssetStatusLogConverter;
import com.demo.gameasset.convert.AssetVersionConverter;
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
import com.demo.gameasset.entity.SysUser;
import com.demo.gameasset.mapper.AssetCategoryMapper;
import com.demo.gameasset.mapper.AssetMapper;
import com.demo.gameasset.mapper.AssetStatusLogMapper;
import com.demo.gameasset.mapper.AssetVersionMapper;
import com.demo.gameasset.mapper.SysUserMapper;
import com.demo.gameasset.service.AssetService;
import com.demo.gameasset.service.ChunkUploadService;
import com.demo.gameasset.service.TaskService;
import com.demo.gameasset.service.support.AssetActionGuard;
import com.demo.gameasset.service.support.AssetReviewNotifier;
import com.demo.gameasset.service.support.AssetNoGenerator;
import com.demo.gameasset.service.support.AssetVersionLock;
import com.demo.gameasset.service.support.ProjectOverviewCache;
import com.demo.gameasset.storage.StorageService;
import com.demo.gameasset.storage.UploadSource;
import com.demo.gameasset.vo.AssetStatusLogVO;
import com.demo.gameasset.vo.AssetVO;
import com.demo.gameasset.vo.AssetVersionCompareVO;
import com.demo.gameasset.vo.AssetVersionVO;
import com.demo.gameasset.vo.Md5CheckVO;
import com.demo.gameasset.vo.PageResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资产服务实现（模块2 的核心）。
 *
 * <p>本类集中了三处最容易出错、也最值得被测试盯住的逻辑：</p>
 * <ol>
 *   <li><b>版本号自增与 {@code is_current} 切换</b>——分布式锁 + 唯一约束双保险，
 *       切换必须「先清后置」且在同一个事务里；</li>
 *   <li><b>状态流转白名单</b>——判定集中在 {@link AssetStatus}，本类只做
 *       「校验 + 落库 + 写日志 + 失效缓存」；</li>
 *   <li><b>MD5 秒传</b>——物理去重是全局的（只省存储、不暴露元数据），
 *       而 {@code check-md5} 接口的查询限定在本项目内（会暴露资产名）。</li>
 * </ol>
 *
 * @author 管理员
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    /** 资产编号分配的查重重试次数：并发下偶尔撞号，重试几乎必然拿到可用号 */
    private static final int ASSET_NO_MAX_ATTEMPTS = 5;

    private final AssetMapper assetMapper;
    private final AssetCategoryMapper categoryMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetStatusLogMapper statusLogMapper;
    private final SysUserMapper sysUserMapper;

    private final StorageService storageService;
    private final ChunkUploadService chunkUploadService;

    private final AssetActionGuard actionGuard;
    private final AssetNoGenerator assetNoGenerator;
    private final AssetVersionLock versionLock;
    private final ProjectOverviewCache overviewCache;

    /**
     * 资产状态流转的通知编排（模块3 提供，文档 5.3 / 5.5 / 5.8）。
     * 独立组件而不是写在本类里：它只依赖「资产 + 项目成员 + 关联任务」三类数据，
     * 与版本、存储这些逻辑没有耦合。
     */
    private final AssetReviewNotifier reviewNotifier;

    /**
     * 相关任务查询（模块3 提供，文档 5.5 的双向联动）。
     * 用服务而不是直接注入 TaskMapper：卡片装配（负责人昵称、资产标签、
     * 评论数）的逻辑只有一份，资产详情页不该再实现一遍。
     */
    private final TaskService taskService;

    // ==================== 列表 ====================

    @Override
    public PageResultVO<AssetVO> page(Long projectId, AssetQueryDTO query) {
        AssetQueryDTO criteria = query == null ? new AssetQueryDTO() : query;
        int pageNo = criteria.normalizedPage();
        int pageSize = criteria.normalizedSize();

        LambdaQueryWrapper<Asset> wrapper = Wrappers.<Asset>lambdaQuery()
                // project_id 是第一条件，不是可选项
                .eq(Asset::getProjectId, projectId)
                .eq(StringUtils.hasText(criteria.getStatus()), Asset::getStatus,
                        upper(criteria.getStatus()))
                .eq(criteria.getOwnerId() != null, Asset::getOwnerId, criteria.getOwnerId())
                .eq(StringUtils.hasText(criteria.getPriority()), Asset::getPriority,
                        upper(criteria.getPriority()))
                .orderByDesc(Asset::getUpdateTime);

        applyCategoryFilter(wrapper, projectId, criteria);
        applyKeywordFilter(wrapper, criteria.getKeyword());

        IPage<Asset> result = assetMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResultVO.of(enrich(result.getRecords(), projectId), result.getTotal(), pageNo, pageSize);
    }

    /**
     * 分类筛选。传一级分类且允许含子级时，展开成 {@code IN (一级, 全部子级)}。
     *
     * <p>子分类ID单独查一次而不是用子查询：分类数量是个位数量级，
     * 一次简单查询换取 SQL 可读性与可测试性，是划算的。</p>
     */
    private void applyCategoryFilter(LambdaQueryWrapper<Asset> wrapper, Long projectId, AssetQueryDTO criteria) {
        if (criteria.getCategoryId() == null) {
            return;
        }
        if (!criteria.includeChildren()) {
            wrapper.eq(Asset::getCategoryId, criteria.getCategoryId());
            return;
        }
        List<AssetCategory> children = categoryMapper.selectList(
                Wrappers.<AssetCategory>lambdaQuery()
                        .eq(AssetCategory::getProjectId, projectId)
                        .eq(AssetCategory::getParentId, criteria.getCategoryId()));
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(criteria.getCategoryId());
        children.forEach(child -> ids.add(child.getId()));
        wrapper.in(Asset::getCategoryId, ids);
    }

    /** 关键字同时匹配资产名称与资产编号：用户可能记得名字，也可能记得编号 */
    private void applyKeywordFilter(LambdaQueryWrapper<Asset> wrapper, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }
        String trimmed = keyword.trim();
        // 括号包裹：MyBatis-Plus 的逻辑删除条件会以 AND 追加到末尾，
        // 不加括号会变成 name LIKE ? OR (asset_no LIKE ? AND deleted = 0)
        wrapper.and(w -> w.like(Asset::getName, trimmed).or().like(Asset::getAssetNo, trimmed));
    }

    // ==================== 新建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetVO create(Long projectId, AssetCreateDTO dto, MultipartFile file,
                          String uploadId, Integer totalChunks) {
        Long userId = UserContext.requireUserId();
        actionGuard.require(projectId, Permission.ASSET_WRITE);

        // 分类必须是本项目的：不带 project_id 就能把资产建到别的项目的分类下
        AssetCategory category = getCategoryInProject(dto.getCategoryId(), projectId);

        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setCategoryId(category.getId());
        asset.setAssetNo(allocateAssetNo(projectId, category.getCode()));
        asset.setName(dto.getName().trim());
        asset.setDescription(dto.getDescription());
        asset.setVersionCount(0);
        asset.setStatus(AssetStatus.IN_PROGRESS.name());
        asset.setPriority(resolvePriority(dto.getPriority()));
        asset.setTags(dto.getTags());
        asset.setOwnerId(userId);
        try {
            assetMapper.insert(asset);
        } catch (DuplicateKeyException e) {
            // 走到这里说明在「查重通过」与「插入」之间，有并发请求抢到了同一个号。
            // 唯一约束在这里是防线而不是报错出口：转成能指导下一步动作的提示，
            // 而不是让全局处理器兜成「数据已存在，请勿重复提交」——
            // 那句话会让人以为自己重复点了提交，实际只是撞了号。
            // （唯一索引只有 uk_project_asset_no 与主键，主键是自增、不会撞）
            log.warn("[资产] 编号被并发抢占 projectId={} assetNo={}", projectId, asset.getAssetNo());
            throw new BizException(ResultCode.CONFLICT, "资产编号分配冲突，请稍后重试");
        }

        UploadVersionDTO versionMeta = new UploadVersionDTO();
        versionMeta.setVersionName(dto.getVersionName());
        versionMeta.setChangeLog(dto.getChangeLog());
        versionMeta.setPolyCount(dto.getPolyCount());
        versionMeta.setTextureSize(dto.getTextureSize());
        versionMeta.setThumbnailUrl(dto.getThumbnailUrl());
        versionMeta.setFileMd5(dto.getFileMd5());
        versionMeta.setFileName(dto.getFileName());

        // 首个版本：此时 asset.id 已回填，存储目录才能带上资产ID
        PreparedFile prepared = resolveFirstVersionSource(projectId, asset.getId(),
                dto.getFileName(), file, uploadId, totalChunks, dto.getFileMd5());
        try {
            insertVersion(asset, prepared, versionMeta, userId);
        } catch (RuntimeException e) {
            // 事务会回滚数据库，但已经落盘的文件不会——补偿删除，
            // 否则每次失败上传都会在存储里留下一份没人引用的垃圾
            deleteNewlyStoredFile(prepared);
            throw e;
        }

        overviewCache.evict(projectId);
        log.info("[资产] 新建成功 projectId={} assetId={} assetNo={} operator={}",
                projectId, asset.getId(), asset.getAssetNo(), userId);
        // 用内存里这份刚写完的实体直接装配 VO，不再回查一次：
        // insertVersion 已经把 currentVersionId / versionCount / status 回写到 asset 上，
        // 回查除了多一次往返，还会让「写完之后状态到底是什么」变得依赖数据库时序
        return enrich(List.of(asset), projectId).get(0);
    }

    /**
     * 解析「首个版本的内容来源」，把三种入口收敛成同一个 {@link PreparedFile}。
     *
     * <p>收敛的意义：新建与「上传新版本」走的是同一个 {@link #insertVersion}，
     * 版本号自增、{@code is_current} 切换、状态回退只有一份实现。</p>
     *
     * @param file        直传文件，可为 null
     * @param uploadId    分片上传会话ID，可为 null
     * @param declaredMd5 客户端声明的 MD5（秒传与校验用）
     */
    private PreparedFile resolveFirstVersionSource(Long projectId, Long assetId, String declaredFileName,
                                                   MultipartFile file, String uploadId, Integer totalChunks,
                                                   String declaredMd5) {
        // 1. 分片上传已完成：按「服务器本地文件」入库，
        //    同样要过 MD5 校验与秒传判定（见 prepareFromSource）
        if ((file == null || file.isEmpty()) && StringUtils.hasText(uploadId)) {
            ChunkUploadService.MergedChunkFile merged =
                    chunkUploadService.merge(uploadId, totalChunks, declaredMd5);
            try {
                String fileName = StringUtils.hasText(declaredFileName)
                        ? declaredFileName : merged.path().getFileName().toString();
                return prepareFromSource(projectId, assetId,
                        UploadSource.ofLocalFile(merged.path(), fileName, merged.size(), merged.md5()));
            } finally {
                cleanupChunkSession(merged.chunkDir());
            }
        }
        // 2. 直传文件；3. 既无文件也无分片时由 prepareFile 走「按 MD5 秒传」
        return prepareFile(projectId, assetId, file, declaredMd5, declaredFileName);
    }

    // ==================== 详情 / 修改 / 删除 ====================

    @Override
    public AssetVO detail(Long assetId, Long projectId) {
        Asset asset = getAssetInProject(assetId, projectId);
        AssetVO vo = enrich(List.of(asset), projectId).get(0);
        // 模块3 的双向联动（文档 5.5）：资产详情要能看到「这个资源还有哪些待办」。
        // 只在详情里查一次；列表不查，理由见 AssetVO#relatedTasks 的注释
        vo.setRelatedTasks(taskService.relatedTasks(projectId, assetId));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetVO update(Long assetId, Long projectId, AssetUpdateDTO dto) {
        Long userId = UserContext.requireUserId();
        actionGuard.require(projectId, Permission.ASSET_WRITE);

        Asset asset = getAssetInProject(assetId, projectId);

        // 逐字段判空：null = 不改这一项。整体 copyProperties 会把没传的字段清空
        var update = Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .eq(Asset::getProjectId, projectId)
                .set(Asset::getName, dto.getName().trim());
        if (dto.getDescription() != null) {
            update.set(Asset::getDescription, dto.getDescription());
        }
        if (dto.getPriority() != null) {
            update.set(Asset::getPriority, resolvePriority(dto.getPriority()));
        }
        if (dto.getTags() != null) {
            update.set(Asset::getTags, dto.getTags());
        }
        if (dto.getCategoryId() != null && !dto.getCategoryId().equals(asset.getCategoryId())) {
            // 换分类同样要先确认目标分类属于本项目
            AssetCategory target = getCategoryInProject(dto.getCategoryId(), projectId);
            update.set(Asset::getCategoryId, target.getId());
            asset.setCategoryId(target.getId());
        }
        assetMapper.update(null, update);

        // 同步内存实体后直接装配 VO，省掉一次回查
        asset.setName(dto.getName().trim());
        if (dto.getDescription() != null) {
            asset.setDescription(dto.getDescription());
        }
        if (dto.getPriority() != null) {
            asset.setPriority(resolvePriority(dto.getPriority()));
        }
        if (dto.getTags() != null) {
            asset.setTags(dto.getTags());
        }

        overviewCache.evict(projectId);
        log.info("[资产] 修改成功 projectId={} assetId={} operator={}", projectId, assetId, userId);
        return enrich(List.of(asset), projectId).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long assetId, Long projectId) {
        Long userId = UserContext.requireUserId();
        actionGuard.require(projectId, Permission.ASSET_DELETE);

        getAssetInProject(assetId, projectId);

        // 逻辑删除：asset 表置 deleted = 1。
        // asset_version / asset_status_log 一行都不动——「历史版本可完整追溯」
        // 是硬要求，删资产不等于抹掉它的版本与审核轨迹
        assetMapper.delete(Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getId, assetId)
                .eq(Asset::getProjectId, projectId));

        overviewCache.evict(projectId);
        log.info("[资产] 已删除（逻辑删除，版本与状态日志保留）projectId={} assetId={} operator={}",
                projectId, assetId, userId);
    }

    // ==================== 版本 ====================

    @Override
    public List<AssetVersionVO> versions(Long assetId, Long projectId) {
        // 先确认资产属于本项目，再查它的版本；
        // asset_version 自己的查询也带 project_id（见下方条件）
        getAssetInProject(assetId, projectId);

        List<AssetVersion> versions = versionMapper.selectList(
                Wrappers.<AssetVersion>lambdaQuery()
                        .eq(AssetVersion::getAssetId, assetId)
                        .eq(AssetVersion::getProjectId, projectId)
                        .orderByDesc(AssetVersion::getVersionNo));
        if (versions.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, SysUser> uploaders = batchUsers(versions.stream()
                .map(AssetVersion::getUploaderId).collect(Collectors.toSet()));
        return versions.stream()
                .map(v -> AssetVersionConverter.toVO(v, displayName(uploaders.get(v.getUploaderId()))))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetVersionVO uploadVersion(Long assetId, Long projectId, UploadVersionDTO meta, MultipartFile file) {
        UploadVersionDTO safeMeta = meta == null ? new UploadVersionDTO() : meta;
        getAssetInProject(assetId, projectId);

        // 文件准备（算 MD5、秒传判定、落盘）放在锁外：
        // 上传几百 MB 的过程中不该一直占着锁，否则同一资产的并发上传完全串行化，
        // 锁的等待超时（3s）会直接把第二个人的上传顶掉
        PreparedFile prepared = prepareFile(projectId, assetId, file, safeMeta.getFileMd5(), safeMeta.getFileName());

        try {
            return versionLock.execute(assetId, () -> {
                // 进临界区后重新取一次资产：锁外读到的状态可能已被并发请求改过，
                // 而 insertVersion 要据它判断状态是否需要回退并写流转日志
                Asset fresh = getAssetInProject(assetId, projectId);
                AssetVersion version = insertVersion(fresh, prepared, safeMeta, UserContext.requireUserId());
                overviewCache.evict(projectId);
                return AssetVersionConverter.toVO(version, currentUserName());
            });
        } catch (RuntimeException e) {
            deleteNewlyStoredFile(prepared);
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetVersionVO uploadVersionByChunks(Long assetId, Long projectId, UploadVersionDTO meta,
                                                String uploadId, Integer totalChunks, String md5) {
        Asset asset = getAssetInProject(assetId, projectId);
        UploadVersionDTO safeMeta = meta == null ? new UploadVersionDTO() : meta;

        ChunkUploadService.MergedChunkFile merged = chunkUploadService.merge(uploadId, totalChunks, md5);
        try {
            // 合并后的临时文件走与直传完全相同的入库路径：
            // 版本号自增、is_current 切换、状态回退只有一份实现
            UploadSource source = UploadSource.ofLocalFile(merged.path(),
                    StringUtils.hasText(safeMeta.getFileName())
                            ? safeMeta.getFileName()
                            : asset.getAssetNo() + "_" + asset.getName(),
                    merged.size(), merged.md5());
            PreparedFile prepared = prepareFromSource(projectId, assetId, source);

            try {
                return versionLock.execute(assetId, () -> {
                    Asset fresh = getAssetInProject(assetId, projectId);
                    AssetVersion version = insertVersion(fresh, prepared, safeMeta, UserContext.requireUserId());
                    overviewCache.evict(projectId);
                    return AssetVersionConverter.toVO(version, currentUserName());
                });
            } catch (RuntimeException e) {
                deleteNewlyStoredFile(prepared);
                throw e;
            }
        } finally {
            // 分片目录整体清掉（含未使用的 merged.tmp）。
            // 入库成功时 merged 文件已被 move 走，这里只是删空目录
            cleanupChunkSession(merged.chunkDir());
        }
    }

    @Override
    public DownloadedFile download(Long assetId, Long versionId, Long projectId) {
        // 双重定位：资产属于本项目 + 版本属于该资产且属于本项目
        getAssetInProject(assetId, projectId);
        AssetVersion version = getVersionInProject(versionId, assetId, projectId);
        return new DownloadedFile(storageService.download(version.getFileKey()),
                version.getFileName(), version.getFileSize() == null ? 0L : version.getFileSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetVersionVO setCurrentVersion(Long assetId, Long versionId, Long projectId) {
        Long userId = UserContext.requireUserId();
        actionGuard.require(projectId, Permission.ASSET_WRITE);

        Asset asset = getAssetInProject(assetId, projectId);
        AssetVersion version = getVersionInProject(versionId, assetId, projectId);

        if (Integer.valueOf(1).equals(version.getIsCurrent())) {
            // 幂等：已经是当前版本就不再写库，也不做无意义的缓存失效
            return AssetVersionConverter.toVO(version, displayName(sysUserMapper.selectById(version.getUploaderId())));
        }

        // ---- 同一事务内完成三步，顺序不能变 ----
        // 1. 先清掉旧的 current
        versionMapper.clearCurrent(assetId, projectId);
        // 2. 再把目标版本置为 current
        versionMapper.update(null,
                Wrappers.<AssetVersion>lambdaUpdate()
                        .eq(AssetVersion::getId, versionId)
                        .eq(AssetVersion::getAssetId, assetId)
                        .eq(AssetVersion::getProjectId, projectId)
                        .set(AssetVersion::getIsCurrent, 1));
        // 3. 主表指向新版本。
        //    注意不动 version_count：回滚不是新增版本，改计数会让「版本总数」
        //    与时间线上的节点数对不上。文件一个都不删，历史仍可下载
        assetMapper.update(null,
                Wrappers.<Asset>lambdaUpdate()
                        .eq(Asset::getId, assetId)
                        .eq(Asset::getProjectId, projectId)
                        .set(Asset::getCurrentVersionId, versionId));

        overviewCache.evict(projectId);
        log.info("[资产] 当前版本已切换 projectId={} assetId={} versionId={} V{} operator={}",
                projectId, assetId, versionId, version.getVersionNo(), userId);

        version.setIsCurrent(1);
        return AssetVersionConverter.toVO(version, displayName(sysUserMapper.selectById(version.getUploaderId())));
    }

    @Override
    public AssetVersionCompareVO compare(Long assetId, Long projectId, Long fromVersionId, Long toVersionId) {
        getAssetInProject(assetId, projectId);
        if (fromVersionId == null || toVersionId == null) {
            throw BizException.badRequest("请指定要对比的两个版本");
        }
        if (fromVersionId.equals(toVersionId)) {
            throw BizException.badRequest("请选择两个不同的版本进行对比");
        }
        AssetVersion from = getVersionInProject(fromVersionId, assetId, projectId);
        AssetVersion to = getVersionInProject(toVersionId, assetId, projectId);

        // 用可变集合而不是 Set.of：同一个上传人上传的两个版本会让 Set.of 抛
        // IllegalArgumentException（元素重复），而这是最常见的对比场景
        Set<Long> uploaderIds = new LinkedHashSet<>();
        uploaderIds.add(from.getUploaderId());
        uploaderIds.add(to.getUploaderId());
        Map<Long, SysUser> uploaders = batchUsers(uploaderIds);
        AssetVersionVO fromVO = AssetVersionConverter.toVO(from, displayName(uploaders.get(from.getUploaderId())));
        AssetVersionVO toVO = AssetVersionConverter.toVO(to, displayName(uploaders.get(to.getUploaderId())));

        AssetVersionCompareVO result = new AssetVersionCompareVO();
        result.setAssetId(assetId);
        result.setFrom(fromVO);
        result.setTo(toVO);

        List<AssetVersionCompareVO.DiffItem> diffs = new ArrayList<>();
        diffs.add(diff("版本号", "V" + from.getVersionNo(), "V" + to.getVersionNo()));
        diffs.add(diff("版本备注", from.getVersionName(), to.getVersionName()));
        diffs.add(diff("原始文件", from.getFileName(), to.getFileName()));
        diffs.add(diff("文件格式", from.getFileExt(), to.getFileExt()));
        diffs.add(diff("文件大小",
                FileUtil.humanSize(nvl(from.getFileSize())), FileUtil.humanSize(nvl(to.getFileSize()))));
        diffs.add(diff("面数", str(from.getPolyCount()), str(to.getPolyCount())));
        diffs.add(diff("贴图尺寸", from.getTextureSize(), to.getTextureSize()));
        diffs.add(diff("变更说明", from.getChangeLog(), to.getChangeLog()));
        diffs.add(diff("上传人", fromVO.getUploaderName(), toVO.getUploaderName()));
        result.setDiffs(diffs);

        if (from.getPolyCount() != null && to.getPolyCount() != null) {
            result.setPolyCountDelta(to.getPolyCount() - from.getPolyCount());
        }
        long sizeDelta = nvl(to.getFileSize()) - nvl(from.getFileSize());
        result.setFileSizeDelta(sizeDelta);
        result.setFileSizeDeltaText(FileUtil.humanSizeDelta(sizeDelta));
        return result;
    }

    // ==================== 状态流转 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetVO changeStatus(Long assetId, Long projectId, AssetStatusDTO dto) {
        Long userId = UserContext.requireUserId();
        Asset asset = getAssetInProject(assetId, projectId);

        String to = upper(dto.getToStatus());
        if (!AssetStatus.isValid(to)) {
            throw BizException.badRequest("非法的资产状态：" + dto.getToStatus()
                    + "，可选值：" + AssetStatus.all());
        }
        String from = asset.getStatus();
        if (!AssetStatus.canTransfer(from, to)) {
            throw BizException.badRequest("不允许的状态流转：" + AssetStatus.label(from)
                    + " → " + AssetStatus.label(to)
                    + "；当前状态可流转到 " + AssetStatus.nextStatuses(from));
        }

        // 细粒度权限：注解只标得了较低的 ASSET_WRITE（因为提交审核 ARTIST 也要能做），
        // 「通过 / 打回 / 废弃」必须在这里再收一道，只有 ASSET_REVIEW（OWNER）具备
        actionGuard.require(projectId, requiredPermission(to));

        // 打回必须说明原因：没有意见的打回等于让美术自己猜哪里不行，
        // 这在协作里比不审核还糟
        if (AssetStatus.REJECTED.name().equals(to) && !StringUtils.hasText(dto.getRemark())) {
            throw BizException.badRequest("打回资产时必须填写审核意见，请说明需要修改的地方");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean reviewed = isReviewResult(to);

        var update = Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .eq(Asset::getProjectId, projectId)
                .set(Asset::getStatus, to);
        if (reviewed) {
            update.set(Asset::getReviewerId, userId)
                    .set(Asset::getReviewTime, now)
                    .set(Asset::getReviewRemark, dto.getRemark());
        }
        assetMapper.update(null, update);

        // 每次流转都留痕：审核过程是协作中的争议点，只留最终状态等于丢掉过程
        statusLogMapper.insert(buildStatusLog(assetId, from, to, userId, dto.getRemark()));

        // 同步内存实体后直接装配 VO：接口要返回「变更后的状态」，
        // 而回查一次不仅多一次往返，还让返回值取决于数据库的可见时序。
        //
        // 这一步必须排在通知编排<b>之前</b>：通知要按「变更后的状态」决定推给谁、
        // 推什么文案。放在后面的话，提交审核（REJECTED → PENDING）会被当成打回，
        // 给美术推一条「资产被打回」——状态是对的，通知是反的，而单测里
        // 单独测通知组件时看不出这一点（真机验证才发现的）。
        asset.setStatus(to);
        if (reviewed) {
            asset.setReviewerId(userId);
            asset.setReviewTime(now);
            asset.setReviewRemark(dto.getRemark());
        }

        // 模块3 补上的联动通知（文档 5.8）：
        //   通过 / 打回 → 资产负责人；提交审核 → 项目 OWNER + 该资产下未完成任务的负责人
        // 与状态日志同理，通知写在同一个事务里：通知表写不进去就是这次流转没完成，
        // 一起回滚即可，不做「流转成功但通知丢了」的静默降级
        reviewNotifier.afterStatusChange(asset, dto.getRemark());

        overviewCache.evict(projectId);
        log.info("[资产] 状态流转 projectId={} assetId={} {} → {} operator={} 已通过审核={}",
                projectId, assetId, from, to, userId, reviewed);
        return enrich(List.of(asset), projectId).get(0);
    }

    @Override
    public List<AssetStatusLogVO> statusLogs(Long assetId, Long projectId) {
        // asset_status_log 表按文档 DDL 没有 project_id 列，因此无法在它的 SQL 上带项目条件。
        // 折中做法：先用带 project_id 的资产查询确认归属，再按 asset_id 查日志——
        // 「查出来再判断归属」在这里是唯一可行的方式，前提是那道资产查询必须带 project_id
        getAssetInProject(assetId, projectId);

        List<AssetStatusLog> logs = statusLogMapper.selectList(
                Wrappers.<AssetStatusLog>lambdaQuery()
                        .eq(AssetStatusLog::getAssetId, assetId)
                        .orderByDesc(AssetStatusLog::getCreateTime)
                        .orderByDesc(AssetStatusLog::getId));
        if (logs.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, SysUser> operators = batchUsers(logs.stream()
                .map(AssetStatusLog::getOperatorId).collect(Collectors.toSet()));
        return logs.stream()
                .map(record -> AssetStatusLogConverter.toVO(record,
                        displayName(operators.get(record.getOperatorId()))))
                .collect(Collectors.toList());
    }

    // ==================== 秒传检查 ====================

    @Override
    public Md5CheckVO checkMd5(Long projectId, Md5CheckDTO dto) {
        String md5 = dto.getMd5().trim().toLowerCase();
        Md5CheckVO vo = new Md5CheckVO();
        vo.setMd5(md5);

        // 限定在本项目内查询。物理层秒传（跨项目复用 file_key 省存储）在
        // prepareFile 里做，那一步不对外暴露任何信息；而这个接口会把
        // 「哪个资产的哪个版本有这份文件」告诉调用方，跨项目返回就是信息泄露
        AssetVersion hit = findVersionByMd5InProject(md5, projectId);
        if (hit == null) {
            vo.setExists(false);
            vo.setMessage("未命中秒传，需要上传文件");
            return vo;
        }

        Asset asset = assetMapper.selectOne(Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getId, hit.getAssetId())
                .eq(Asset::getProjectId, projectId));

        vo.setExists(true);
        vo.setFileKey(hit.getFileKey());
        vo.setVersionId(hit.getId());
        vo.setVersionNo(hit.getVersionNo());
        vo.setFileName(hit.getFileName());
        vo.setFileSize(hit.getFileSize());
        if (asset != null) {
            vo.setAssetId(asset.getId());
            vo.setAssetNo(asset.getAssetNo());
            vo.setAssetName(asset.getName());
        }
        vo.setMessage("命中秒传：本项目「" + (asset == null ? "未知资产" : asset.getName())
                + "」已有相同文件（V" + hit.getVersionNo() + "），可跳过上传");
        return vo;
    }

    // ==================== 核心：文件准备与版本入库 ====================

    /**
     * 已准备好的待入库文件。
     *
     * @param md5            内容 MD5
     * @param originalFilename 原始文件名
     * @param size           字节数
     * @param fileKey        存储 key（可能是复用已有文件的 key）
     * @param newlyStored    本次是否真的写了磁盘（回滚时决定要不要补偿删除）
     */
    private record PreparedFile(String md5, String originalFilename, long size,
                               String fileKey, boolean newlyStored) {
    }

    /**
     * 准备待入库文件（文档 5.2 的「上传前算 MD5 → 命中则复用 fileKey」）。
     *
     * <p>两条路径：</p>
     * <ul>
     *   <li><b>带了文件</b>：服务端算 MD5 → 全局查找同 MD5 的历史版本 →
     *       命中则复用 {@code fileKey}，跳过物理上传；未命中才落盘。</li>
     *   <li><b>没带文件</b>：要求 meta 里的 {@code fileMd5} 在本项目已有记录
     *       （前端先调 {@code check-md5} 得到的结论），直接复用其 fileKey。</li>
     * </ul>
     */
    private PreparedFile prepareFile(Long projectId, Long assetId, MultipartFile file,
                                     String declaredMd5, String declaredFileName) {
        if (file != null && !file.isEmpty()) {
            return prepareFromSource(projectId, assetId, UploadSource.of(file), declaredMd5);
        }
        if (!StringUtils.hasText(declaredMd5)) {
            throw BizException.badRequest("请上传文件，或提供可用于秒传的文件 MD5");
        }
        String md5 = declaredMd5.trim().toLowerCase();
        AssetVersion existing = findVersionByMd5InProject(md5, projectId);
        if (existing == null) {
            throw BizException.badRequest("本项目内未找到该 MD5 对应的文件，无法秒传，请上传文件");
        }
        String fileName = StringUtils.hasText(declaredFileName)
                ? declaredFileName : existing.getFileName();
        log.info("[秒传] 跳过物理上传 projectId={} md5={} fileKey={}", projectId, md5, existing.getFileKey());
        return new PreparedFile(md5, fileName,
                existing.getFileSize() == null ? 0L : existing.getFileSize(),
                existing.getFileKey(), false);
    }

    /** 从上传内容准备文件；{@code declaredMd5} 存在时与计算结果比对 */
    private PreparedFile prepareFromSource(Long projectId, Long assetId, UploadSource source) {
        return prepareFromSource(projectId, assetId, source, null);
    }

    private PreparedFile prepareFromSource(Long projectId, Long assetId, UploadSource source, String declaredMd5) {
        String md5 = source.md5Hex();
        if (StringUtils.hasText(declaredMd5) && !declaredMd5.trim().equalsIgnoreCase(md5)) {
            throw BizException.badRequest("文件 MD5 校验失败：客户端声明 " + declaredMd5
                    + "，服务端实际 " + md5);
        }

        // 全局物理去重：命中任意项目、任意资产的同 MD5 文件就复用 fileKey。
        // 这里只复用存储路径、不返回任何业务元数据，因此不构成跨项目信息泄露；
        // 而它带来的收益很直接——同一份公共贴图被多个项目引用时只存一份
        AssetVersion existing = findAnyVersionByMd5(md5);
        if (existing != null) {
            log.info("[秒传] 命中已有物理文件，跳过上传 md5={} fileKey={}", md5, existing.getFileKey());
            return new PreparedFile(md5, source.originalFilename(), source.size(), existing.getFileKey(), false);
        }

        // 未命中：真正落盘。目录形如 {projectId}/{assetId}
        String dir = projectId + "/" + assetId;
        String fileKey = source.store(storageService, dir);
        return new PreparedFile(md5, source.originalFilename(), source.size(), fileKey, true);
    }

    /**
     * 写入一条新版本，并完成「版本号自增 + 当前版本切换 + 主表更新」。
     *
     * <p><b>本方法必须在 {@link AssetVersionLock} 的临界区内调用。</b>
     * 版本号取 {@code MAX(version_no) + 1}，没有锁时两个并发请求会算出同一个号；
     * 唯一约束 {@code uk_asset_version} 会拦住其中一个，但用户看到的是上传失败。</p>
     *
     * <p>顺序严格是「先清 current 再置新」，反了会出现两条 {@code is_current = 1}，
     * 而 {@code asset.current_version_id} 又只指向其中一条，
     * 表现为「时间线里有两个当前版本」这种很难解释的状态。</p>
     */
    private AssetVersion insertVersion(Asset asset, PreparedFile prepared,
                                       UploadVersionDTO meta, Long uploaderId) {
        Long assetId = asset.getId();
        Long projectId = asset.getProjectId();

        int nextNo = versionMapper.selectMaxVersionNo(assetId, projectId) + 1;

        // 1. 清掉旧标记
        versionMapper.clearCurrent(assetId, projectId);

        // 2. 插入新版本并置为 current
        AssetVersion version = new AssetVersion();
        version.setAssetId(assetId);
        version.setProjectId(projectId);
        version.setVersionNo(nextNo);
        version.setVersionName(StringUtils.hasText(meta.getVersionName())
                ? meta.getVersionName().trim() : "V" + nextNo);
        version.setFileName(prepared.originalFilename());
        version.setFileKey(prepared.fileKey());
        version.setFileSize(prepared.size());
        version.setFileExt(FileUtil.ext(prepared.originalFilename()));
        version.setFileMd5(prepared.md5());
        version.setThumbnailUrl(meta.getThumbnailUrl());
        version.setPolyCount(meta.getPolyCount());
        version.setTextureSize(meta.getTextureSize());
        version.setChangeLog(meta.getChangeLog());
        version.setUploaderId(uploaderId);
        version.setIsCurrent(1);
        versionMapper.insert(version);

        // 3. 主表：当前版本、版本数、状态回退为制作中（新版本需重新审核）
        String previousStatus = asset.getStatus();
        boolean statusRolledBack = !AssetStatus.IN_PROGRESS.name().equals(previousStatus);
        var update = Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .eq(Asset::getProjectId, projectId)
                .set(Asset::getCurrentVersionId, version.getId())
                .set(Asset::getVersionCount, nextNo);
        if (statusRolledBack) {
            update.set(Asset::getStatus, AssetStatus.IN_PROGRESS.name());
        }
        assetMapper.update(null, update);

        if (statusRolledBack) {
            // 状态回退也要留痕：否则「这个资产昨天还是已通过，今天怎么变回制作中了」
            // 在状态历史里看不到任何解释
            statusLogMapper.insert(buildStatusLog(assetId, previousStatus,
                    AssetStatus.IN_PROGRESS.name(), uploaderId,
                    "上传新版本 V" + nextNo + "，状态自动回退为制作中，需重新审核"));
        }
        asset.setCurrentVersionId(version.getId());
        asset.setVersionCount(nextNo);
        asset.setStatus(AssetStatus.IN_PROGRESS.name());
        return version;
    }

    // ==================== 内部工具 ====================

    /**
     * 按 ID + 项目查资产（第二层防护的落点）。
     *
     * <p>权威查询带 {@code project_id}；只有查不到时用一个仅含主键的计数探针
     * 区分「越权（403）」与「不存在（404）」。探针不取回业务数据，
     * 目的只是不让 404 把越权掩盖掉——文档 5.1 与测试要点都要求越权必须是 403。</p>
     */
    private Asset getAssetInProject(Long assetId, Long projectId) {
        if (assetId == null) {
            throw BizException.badRequest("资产ID不能为空");
        }
        if (projectId == null) {
            // 放进 SQL 里会变成 project_id = null（永不匹配），
            // 表面上是查不到，实际是把「上下文缺失」伪装成了「资源不存在」
            throw BizException.badRequest("项目上下文缺失，无法定位资产");
        }
        Asset asset = assetMapper.selectOne(
                Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getId, assetId)
                        .eq(Asset::getProjectId, projectId));
        if (asset != null) {
            return asset;
        }
        Long exists = assetMapper.selectCount(
                Wrappers.<Asset>lambdaQuery().eq(Asset::getId, assetId));
        if (exists != null && exists > 0) {
            throw BizException.forbidden("该资产不属于当前项目，无权访问");
        }
        throw BizException.notFound("资产不存在或已删除");
    }

    /** 按 ID + 资产 + 项目查版本；查不到同样区分 403 与 404 */
    private AssetVersion getVersionInProject(Long versionId, Long assetId, Long projectId) {
        if (versionId == null) {
            throw BizException.badRequest("版本ID不能为空");
        }
        AssetVersion version = versionMapper.selectOne(
                Wrappers.<AssetVersion>lambdaQuery()
                        .eq(AssetVersion::getId, versionId)
                        .eq(AssetVersion::getAssetId, assetId)
                        .eq(AssetVersion::getProjectId, projectId));
        if (version != null) {
            return version;
        }
        Long exists = versionMapper.selectCount(
                Wrappers.<AssetVersion>lambdaQuery().eq(AssetVersion::getId, versionId));
        if (exists != null && exists > 0) {
            throw BizException.forbidden("该版本不属于当前资产或项目，无权访问");
        }
        throw BizException.notFound("版本不存在");
    }

    private AssetCategory getCategoryInProject(Long categoryId, Long projectId) {
        AssetCategory category = categoryMapper.selectOne(
                Wrappers.<AssetCategory>lambdaQuery()
                        .eq(AssetCategory::getId, categoryId)
                        .eq(AssetCategory::getProjectId, projectId));
        if (category == null) {
            throw BizException.badRequest("分类不存在或不属于当前项目");
        }
        return category;
    }

    /** 全局按 MD5 找一条历史版本（物理秒传用，不做项目过滤） */
    private AssetVersion findAnyVersionByMd5(String md5) {
        if (!StringUtils.hasText(md5)) {
            return null;
        }
        return versionMapper.selectOne(Wrappers.<AssetVersion>lambdaQuery()
                .eq(AssetVersion::getFileMd5, md5.trim().toLowerCase())
                .last("LIMIT 1"));
    }

    /** 本项目内按 MD5 找历史版本（秒传检查接口用） */
    private AssetVersion findVersionByMd5InProject(String md5, Long projectId) {
        if (!StringUtils.hasText(md5)) {
            return null;
        }
        return versionMapper.selectOne(Wrappers.<AssetVersion>lambdaQuery()
                .eq(AssetVersion::getFileMd5, md5.trim().toLowerCase())
                .eq(AssetVersion::getProjectId, projectId)
                .orderByAsc(AssetVersion::getId)
                .last("LIMIT 1"));
    }

    /**
     * 分配资产编号（文档 3.3 第 1 点）。
     *
     * <p>Redis {@code INCR} 给出候选号，这里再查一次库确认没被占用。
     * 「查重 + 重试」是必要的：Redis 序列被清空、或从别的环境切过来时，
     * INCR 会从 1 重新开始，必然撞上已有编号。
     * 最后一道防线是 {@code uk_project_asset_no} 唯一约束。</p>
     *
     * <p><b>查重必须连逻辑删除的资产一起数</b>（{@code countByAssetNoIncludeDeleted}）：
     * 用 {@code selectCount} 会因为 {@code @TableLogic} 只看未删除的行，
     * 把被删资产的编号误判为可用——插入时撞上唯一约束，
     * 用户看到的是「数据已存在，请勿重复提交」，与真实原因南辕北辙。</p>
     */
    private String allocateAssetNo(Long projectId, String categoryCode) {
        for (int attempt = 0; attempt < ASSET_NO_MAX_ATTEMPTS; attempt++) {
            String candidate = assetNoGenerator.next(projectId, categoryCode);
            if (assetMapper.countByAssetNoIncludeDeleted(projectId, candidate) == 0) {
                return candidate;
            }
            log.warn("[资产编号] 候选号已被占用，重新分配 projectId={} candidate={} 第{}次重试",
                    projectId, candidate, attempt + 1);
        }
        throw new BizException(ResultCode.CONFLICT, "资产编号分配冲突，请稍后重试");
    }

    /**
     * 批量装配列表 / 详情用的 VO。
     *
     * <p>分类、当前版本、用户各自批量查一次，避免逐条查询产生 N+1——
     * 资产列表是筛选交互的主路径，慢一点用户立刻能感觉到。</p>
     */
    private List<AssetVO> enrich(List<Asset> assets, Long projectId) {
        if (assets == null || assets.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, AssetCategory> categories = batchCategories(assets);
        Map<Long, AssetVersion> currentVersions = batchCurrentVersions(assets, projectId);
        Set<Long> userIds = new LinkedHashSet<>();
        assets.forEach(a -> userIds.add(a.getOwnerId()));
        assets.forEach(a -> userIds.add(a.getReviewerId()));
        Map<Long, SysUser> users = batchUsers(userIds);
        Map<Long, SysUser> uploaders = batchUsers(currentVersions.values().stream()
                .map(AssetVersion::getUploaderId).collect(Collectors.toSet()));

        List<AssetVO> result = new ArrayList<>(assets.size());
        for (Asset asset : assets) {
            AssetVersion current = currentVersions.get(asset.getCurrentVersionId());
            AssetVersionVO currentVO = current == null ? null
                    : AssetVersionConverter.toVO(current, displayName(uploaders.get(current.getUploaderId())));
            result.add(AssetConverter.toVO(asset,
                    categories.get(asset.getCategoryId()),
                    displayName(users.get(asset.getOwnerId())),
                    displayName(users.get(asset.getReviewerId())),
                    currentVO,
                    currentVO == null ? null : currentVO.getUploaderName()));
        }
        return result;
    }

    private Map<Long, AssetCategory> batchCategories(List<Asset> assets) {
        Set<Long> ids = assets.stream().map(Asset::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AssetCategory::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, AssetVersion> batchCurrentVersions(List<Asset> assets, Long projectId) {
        Set<Long> ids = assets.stream().map(Asset::getCurrentVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return versionMapper.selectList(Wrappers.<AssetVersion>lambdaQuery()
                        .in(AssetVersion::getId, ids)
                        .eq(AssetVersion::getProjectId, projectId)).stream()
                .collect(Collectors.toMap(AssetVersion::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, SysUser> batchUsers(Set<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return sysUserMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    private AssetStatusLog buildStatusLog(Long assetId, String from, String to, Long operatorId, String remark) {
        AssetStatusLog log = new AssetStatusLog();
        log.setAssetId(assetId);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorId(operatorId);
        log.setRemark(remark);
        return log;
    }

    /** 目标状态对应的权限点（文档 5.3 的 switch） */
    private Permission requiredPermission(String toStatus) {
        return switch (toStatus) {
            // 提交审核：OWNER 与 ARTIST 都可以（两者都有 ASSET_WRITE）
            case "PENDING" -> Permission.ASSET_WRITE;
            // 审核结论与废弃：仅 OWNER（唯一持有 ASSET_REVIEW 的角色）
            case "APPROVED", "REJECTED", "DEPRECATED" -> Permission.ASSET_REVIEW;
            // 退回继续改（REJECTED / APPROVED / DEPRECATED → IN_PROGRESS）
            default -> Permission.ASSET_WRITE;
        };
    }

    /** APPROVED / REJECTED 属于「审核结论」，需要写审核人、审核时间与意见 */
    private boolean isReviewResult(String status) {
        return AssetStatus.APPROVED.name().equals(status) || AssetStatus.REJECTED.name().equals(status);
    }

    /** 清理分片会话目录（尽力而为，失败由定时任务兜底） */
    private void cleanupChunkSession(Path chunkDir) {
        if (chunkDir == null) {
            return;
        }
        try (var walk = Files.walk(chunkDir)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("[分片上传] 会话目录清理失败 dir={} 原因={}", chunkDir, e.getMessage());
        }
    }

    /** 失败回滚时删除本次新写的物理文件：数据库回滚了，磁盘不会 */
    private void deleteNewlyStoredFile(PreparedFile prepared) {
        if (prepared != null && prepared.newlyStored()) {
            boolean deleted = storageService.delete(prepared.fileKey());
            log.warn("[资产] 入库失败，已补偿删除本次上传的文件 fileKey={} 删除结果={}",
                    prepared.fileKey(), deleted);
        }
    }

    private String resolvePriority(String priority) {
        if (!StringUtils.hasText(priority)) {
            return AssetPriority.DEFAULT.name();
        }
        AssetPriority value = AssetPriority.of(priority);
        if (value == null) {
            throw BizException.badRequest("非法的优先级：" + priority
                    + "，可选值：" + AssetPriority.all());
        }
        return value.name();
    }

    private AssetVersionCompareVO.DiffItem diff(String field, String before, String after) {
        String b = StringUtils.hasText(before) ? before : "—";
        String a = StringUtils.hasText(after) ? after : "—";
        return new AssetVersionCompareVO.DiffItem(field, b, a, !b.equals(a));
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String currentUserName() {
        String username = UserContext.getUsername();
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return username;
    }

    private String displayName(SysUser user) {
        if (user == null) {
            return null;
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }
}
