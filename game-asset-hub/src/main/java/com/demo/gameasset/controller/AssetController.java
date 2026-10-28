package com.demo.gameasset.controller;

import com.demo.gameasset.common.annotation.ProjectPermission;
import com.demo.gameasset.common.enums.Permission;
import com.demo.gameasset.common.result.Result;
import com.demo.gameasset.context.UserContext;
import com.demo.gameasset.dto.AssetCreateDTO;
import com.demo.gameasset.dto.AssetQueryDTO;
import com.demo.gameasset.dto.AssetStatusDTO;
import com.demo.gameasset.dto.AssetUpdateDTO;
import com.demo.gameasset.dto.ChunkInitDTO;
import com.demo.gameasset.dto.Md5CheckDTO;
import com.demo.gameasset.dto.UploadVersionDTO;
import com.demo.gameasset.service.AssetService;
import com.demo.gameasset.service.ChunkUploadService;
import com.demo.gameasset.vo.AssetStatusLogVO;
import com.demo.gameasset.vo.AssetVO;
import com.demo.gameasset.vo.AssetVersionCompareVO;
import com.demo.gameasset.vo.AssetVersionVO;
import com.demo.gameasset.vo.ChunkInitVO;
import com.demo.gameasset.vo.Md5CheckVO;
import com.demo.gameasset.vo.PageResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 资产与版本接口（模块2，文档 4.2）。
 *
 * <p><b>两条路径形态</b>：</p>
 * <ul>
 *   <li>{@code /api/projects/{projectId}/assets}——项目维度，项目ID 来自路径；</li>
 *   <li>{@code /api/assets/{id}/...}——资产维度，路径里<b>没有</b>项目段，
 *       项目ID 由 {@code ProjectIdResolver} 从 {@code X-Project-Id} 请求头取，
 *       前端拦截器已全局注入。</li>
 * </ul>
 *
 * <p><b>为什么没有类级 {@code @RequestMapping}</b>：两种前缀并存，
 * 用类级注解反而要写两个控制器类。写成方法级完整路径更直白，
 * 也让「哪些接口在路径里带项目」一目了然。</p>
 *
 * <p><b>上传请求格式</b>（文档 4.2）：{@code multipart/form-data}，
 * 文件部分名为 {@code file}，元数据部分名为 {@code meta} 且
 * {@code Content-Type: application/json}。因此上传接口用
 * {@code @RequestPart} 而不是 {@code @RequestParam}——后者拿不到 JSON 反序列化。</p>
 *
 * @author 管理员
 */
@RestController
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final ChunkUploadService chunkUploadService;

    // ==================== 项目维度：列表与新建 ====================

    /**
     * 资产列表（分页 + 分类/状态/负责人/优先级/关键字筛选）。
     *
     * <p>查询参数自动绑定到 {@link AssetQueryDTO}，不写 {@code @ModelAttribute}
     * 是因为 Spring MVC 对非简单类型的未注解参数默认就走模型属性绑定。</p>
     */
    @ProjectPermission(Permission.ASSET_READ)
    @GetMapping("/api/projects/{projectId}/assets")
    public Result<PageResultVO<AssetVO>> page(@PathVariable("projectId") Long projectId,
                                              AssetQueryDTO query) {
        return Result.success(assetService.page(projectId, query));
    }

    /**
     * 新建资产（含首个版本）。
     *
     * <p>首个版本的内容有<b>三条互斥的来源</b>，按优先级取第一个可用的：</p>
     * <ol>
     *   <li>{@code file} 部分——文档 4.2 的直传格式，适合小文件与 curl 调试；</li>
     *   <li>{@code uploadId} + {@code totalChunks} 请求参数——分片上传已完成，
     *       服务端合并后入库。前端对 2GB 级别的资产统一走分片，
     *       因此新建入口也要能接住，否则「新建大资产」就只能退回整包直传；</li>
     *   <li>{@code meta.fileMd5}——秒传，本项目内已有该文件时直接复用。</li>
     * </ol>
     */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PostMapping("/api/projects/{projectId}/assets")
    public Result<AssetVO> create(@PathVariable("projectId") Long projectId,
                                  @RequestPart(value = "file", required = false) MultipartFile file,
                                  @RequestPart("meta") @Valid AssetCreateDTO meta,
                                  @RequestParam(value = "uploadId", required = false) String uploadId,
                                  @RequestParam(value = "totalChunks", required = false) Integer totalChunks) {
        return Result.success("资产已创建",
                assetService.create(projectId, meta, file, uploadId, totalChunks));
    }

    // ==================== 资产维度：详情与维护 ====================

    /** 资产详情（含元数据与当前版本） */
    @ProjectPermission(Permission.ASSET_READ)
    @GetMapping("/api/assets/{id}")
    public Result<AssetVO> detail(@PathVariable("id") Long id) {
        return Result.success(assetService.detail(id, UserContext.requireProjectId()));
    }

    /** 修改资产信息（名称、描述、分类、优先级、标签） */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PutMapping("/api/assets/{id}")
    public Result<AssetVO> update(@PathVariable("id") Long id,
                                  @Valid @RequestBody AssetUpdateDTO dto) {
        return Result.success("资产信息已更新",
                assetService.update(id, UserContext.requireProjectId(), dto));
    }

    /**
     * 删除资产（逻辑删除）。
     * 版本记录与状态日志全部保留，历史仍可追溯。
     */
    @ProjectPermission(Permission.ASSET_DELETE)
    @DeleteMapping("/api/assets/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        assetService.delete(id, UserContext.requireProjectId());
        return Result.success("资产已删除", null);
    }

    // ==================== 版本 ====================

    /** 版本历史列表（按版本号倒序） */
    @ProjectPermission(Permission.ASSET_READ)
    @GetMapping("/api/assets/{id}/versions")
    public Result<List<AssetVersionVO>> versions(@PathVariable("id") Long id) {
        return Result.success(assetService.versions(id, UserContext.requireProjectId()));
    }

    /** 上传新版本（文件可缺省以走秒传） */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PostMapping("/api/assets/{id}/versions")
    public Result<AssetVersionVO> uploadVersion(@PathVariable("id") Long id,
                                                @RequestPart(value = "file", required = false) MultipartFile file,
                                                @RequestPart("meta") @Valid UploadVersionDTO meta) {
        AssetVersionVO vo = assetService.uploadVersion(id, UserContext.requireProjectId(), meta, file);
        return Result.success("版本 V" + vo.getVersionNo() + " 上传成功", vo);
    }

    /**
     * 下载指定版本。
     *
     * <p>返回二进制流而不是 302 重定向到对象存储：当前默认实现是本地存储，
     * 没有可直连的 URL。若切到 MinIO，可按文档 10.5 改成返回预签名地址的
     * 302，前端不需要改。</p>
     */
    @ProjectPermission(Permission.ASSET_READ)
    @GetMapping("/api/assets/{id}/versions/{vid}/download")
    public ResponseEntity<Resource> download(@PathVariable("id") Long id,
                                             @PathVariable("vid") Long vid) {
        AssetService.DownloadedFile file =
                assetService.download(id, vid, UserContext.requireProjectId());
        // 文件名用 RFC 5987 编码（filename*=UTF-8''...）：资产原名含中文与空格很常见，
        // 直接拼进 header 会让浏览器拿到乱码文件名
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.size())
                .body(file.resource());
    }

    /** 将指定版本设为当前版本（回滚）。只改标记位，历史文件一个不删 */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PatchMapping("/api/assets/{id}/versions/{vid}/current")
    public Result<AssetVersionVO> setCurrent(@PathVariable("id") Long id,
                                             @PathVariable("vid") Long vid) {
        AssetVersionVO vo = assetService.setCurrentVersion(id, vid, UserContext.requireProjectId());
        return Result.success("已切换到版本 V" + vo.getVersionNo(), vo);
    }

    /** 两个版本的元数据对比 */
    @ProjectPermission(Permission.ASSET_READ)
    @GetMapping("/api/assets/{id}/versions/compare")
    public Result<AssetVersionCompareVO> compare(@PathVariable("id") Long id,
                                                 @RequestParam("from") Long from,
                                                 @RequestParam("to") Long to) {
        return Result.success(assetService.compare(id, UserContext.requireProjectId(), from, to));
    }

    // ==================== 状态流转 ====================

    /**
     * 资产状态流转（提交审核 / 通过 / 打回 / 废弃 / 退回继续改）。
     *
     * <p>注解标的是 {@code ASSET_WRITE} 而不是 {@code ASSET_REVIEW}：
     * 「提交审核」ARTIST 也要能做，而注解只能标一个权限点。
     * 「通过 / 打回 / 废弃」需要更高的 {@code ASSET_REVIEW}，
     * 由服务层按目标状态再判一道（文档 5.3）。</p>
     */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PatchMapping("/api/assets/{id}/status")
    public Result<AssetVO> changeStatus(@PathVariable("id") Long id,
                                        @Valid @RequestBody AssetStatusDTO dto) {
        AssetVO vo = assetService.changeStatus(id, UserContext.requireProjectId(), dto);
        return Result.success("资产状态已变更为「" + vo.getStatusLabel() + "」", vo);
    }

    /** 状态流转历史（可追溯） */
    @ProjectPermission(Permission.ASSET_READ)
    @GetMapping("/api/assets/{id}/status-logs")
    public Result<List<AssetStatusLogVO>> statusLogs(@PathVariable("id") Long id) {
        return Result.success(assetService.statusLogs(id, UserContext.requireProjectId()));
    }

    // ==================== 秒传与分片上传 ====================

    /** 秒传检查：本项目内是否已有相同 MD5 的文件 */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PostMapping("/api/assets/upload/check-md5")
    public Result<Md5CheckVO> checkMd5(@Valid @RequestBody Md5CheckDTO dto) {
        return Result.success(assetService.checkMd5(UserContext.requireProjectId(), dto));
    }

    /** 分片上传：初始化会话，返回 uploadId 与服务端分片大小 */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PostMapping("/api/assets/upload/init")
    public Result<ChunkInitVO> initChunkUpload(@Valid @RequestBody ChunkInitDTO dto) {
        return Result.success("上传会话已创建", chunkUploadService.init(dto));
    }

    /**
     * 分片上传：上传单个分片。
     *
     * <p>这里用 {@code @RequestParam} 接 {@code uploadId} / {@code index}：
     * 它们在 multipart 里是普通文本字段，用 {@code @RequestParam} 即可，
     * 也让前端可以直接 {@code fd.append('uploadId', ...)} 而不必包 Blob。</p>
     */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PostMapping("/api/assets/upload/chunk")
    public Result<Void> uploadChunk(@RequestParam("uploadId") String uploadId,
                                    @RequestParam("index") Integer index,
                                    @RequestPart("chunk") MultipartFile chunk) {
        chunkUploadService.saveChunk(uploadId, index, chunk);
        return Result.success("分片已接收", null);
    }

    /**
     * 分片上传：合并并创建版本。
     *
     * <p>服务端按序合并、校验整包 MD5，然后走与直传<b>完全相同</b>的入库流程，
     * 因此版本号自增、{@code is_current} 切换、状态回退这些规则不会有两份实现。</p>
     */
    @ProjectPermission(Permission.ASSET_WRITE)
    @PostMapping("/api/assets/upload/complete")
    public Result<AssetVersionVO> completeChunkUpload(@RequestParam("assetId") Long assetId,
                                                      @RequestParam("uploadId") String uploadId,
                                                      @RequestParam("totalChunks") Integer totalChunks,
                                                      @RequestParam(value = "md5", required = false) String md5,
                                                      @RequestPart("meta") @Valid UploadVersionDTO meta) {
        AssetVersionVO vo = assetService.uploadVersionByChunks(
                assetId, UserContext.requireProjectId(), meta, uploadId, totalChunks, md5);
        return Result.success("分片合并完成，版本 V" + vo.getVersionNo() + " 已创建", vo);
    }
}
