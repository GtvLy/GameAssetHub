package com.zhangchi.gameasset.service;

import com.zhangchi.gameasset.dto.AssetCreateDTO;
import com.zhangchi.gameasset.dto.AssetQueryDTO;
import com.zhangchi.gameasset.dto.AssetStatusDTO;
import com.zhangchi.gameasset.dto.AssetUpdateDTO;
import com.zhangchi.gameasset.dto.Md5CheckDTO;
import com.zhangchi.gameasset.dto.UploadVersionDTO;
import com.zhangchi.gameasset.vo.AssetStatusLogVO;
import com.zhangchi.gameasset.vo.AssetVO;
import com.zhangchi.gameasset.vo.AssetVersionCompareVO;
import com.zhangchi.gameasset.vo.AssetVersionVO;
import com.zhangchi.gameasset.vo.Md5CheckVO;
import com.zhangchi.gameasset.vo.PageResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 资产服务（文档 4.2 资产与版本接口）。
 *
 * <p><b>接口契约里 projectId 一律显式传入</b>，不从 {@code UserContext} 里隐式取：
 * 每个方法都能被单独调用与验证，也避免「调用方忘了设上下文就查到全表」。
 * 控制器层负责从 {@code UserContext.requireProjectId()} 取出后传入。</p>
 *
 * @author 张忠驰
 */
public interface AssetService {

    /** 资产列表（分页 + 分类/状态/负责人/优先级/关键字筛选） */
    PageResultVO<AssetVO> page(Long projectId, AssetQueryDTO query);

    /**
     * 新建资产（含首个版本）。
     *
     * <p>首个版本的内容来源有三种，按优先级取第一个可用的：</p>
     * <ol>
     *   <li>{@code file}——普通的 multipart 直传（文档 4.2 的请求格式）；</li>
     *   <li>{@code uploadId}——分片上传已完成，服务端合并后的临时文件。
     *       前端对 2GB 级别的资产统一走分片，因此新建入口也要能接住；</li>
     *   <li>{@code dto.fileMd5}——秒传：本项目内已存在该 MD5 的文件，复用其 fileKey。</li>
     * </ol>
     *
     * @param file        上传的首版文件，可为 null
     * @param uploadId    分片上传会话ID，可为 null
     * @param totalChunks 分片总数，与 uploadId 同时提供才有意义
     */
    AssetVO create(Long projectId, AssetCreateDTO dto, MultipartFile file,
                   String uploadId, Integer totalChunks);

    /** 资产详情（含当前版本完整信息） */
    AssetVO detail(Long assetId, Long projectId);

    /** 修改资产信息（名称、描述、分类、优先级、标签） */
    AssetVO update(Long assetId, Long projectId, AssetUpdateDTO dto);

    /** 删除资产（逻辑删除，版本与状态日志全部保留） */
    void delete(Long assetId, Long projectId);

    /** 版本历史列表（按版本号倒序，时间线用） */
    List<AssetVersionVO> versions(Long assetId, Long projectId);

    /**
     * 上传新版本。
     *
     * @param file 上传文件；为 null 时走秒传路径
     */
    AssetVersionVO uploadVersion(Long assetId, Long projectId, UploadVersionDTO meta, MultipartFile file);

    /**
     * 分片上传完成：按序合并分片、校验 MD5，然后走与新版本上传<b>完全相同</b>的入库流程。
     *
     * @param uploadId    分片上传会话ID
     * @param totalChunks 分片总数
     * @param md5         整包 MD5（可选），提供时与合并结果比对
     */
    AssetVersionVO uploadVersionByChunks(Long assetId, Long projectId, UploadVersionDTO meta,
                                         String uploadId, Integer totalChunks, String md5);

    /** 下载指定版本 */
    DownloadedFile download(Long assetId, Long versionId, Long projectId);

    /** 把指定版本设为当前版本（回滚）。只改标记位，不删除任何历史文件 */
    AssetVersionVO setCurrentVersion(Long assetId, Long versionId, Long projectId);

    /** 两个版本的元数据对比 */
    AssetVersionCompareVO compare(Long assetId, Long projectId, Long fromVersionId, Long toVersionId);

    /** 资产状态流转（含审核） */
    AssetVO changeStatus(Long assetId, Long projectId, AssetStatusDTO dto);

    /** 状态流转历史（可追溯） */
    List<AssetStatusLogVO> statusLogs(Long assetId, Long projectId);

    /** 秒传检查：本项目内是否已有相同 MD5 的文件 */
    Md5CheckVO checkMd5(Long projectId, Md5CheckDTO dto);

    /**
     * 下载内容。
     *
     * @param resource       文件资源
     * @param fileName       原始文件名（拼 Content-Disposition 用）
     * @param size           字节数
     */
    record DownloadedFile(org.springframework.core.io.Resource resource, String fileName, long size) {
    }
}
