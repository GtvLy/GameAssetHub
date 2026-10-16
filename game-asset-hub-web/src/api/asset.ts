import request from './request'
import type {
  AssetCategoryDTO,
  AssetCategoryVO,
  AssetCreateDTO,
  AssetQuery,
  AssetStatusDTO,
  AssetStatusLogVO,
  AssetUpdateDTO,
  AssetVO,
  AssetVersionCompareVO,
  AssetVersionVO,
  ChunkInitDTO,
  ChunkInitVO,
  Md5CheckDTO,
  Md5CheckVO,
  PageResultVO,
  UploadVersionDTO
} from '@/types/asset'

/**
 * 模块2 的接口封装。
 *
 * 两条路径形态要留意：
 *   - `/projects/{pid}/...` 项目维度，项目ID 在路径里；
 *   - `/assets/{id}/...`  资产维度，路径里没有项目段，
 *     后端从请求头 `X-Project-Id` 取项目上下文——这个头由 request.ts
 *     的拦截器统一注入，这里不需要手写。
 */

// ==================== 分类 ====================

/** 分类树（一级 + 二级，带各分类下的资产数量） */
export function fetchCategoryTree(projectId: number) {
  return request.get<never, AssetCategoryVO[]>(`/projects/${projectId}/categories`)
}

export function createCategory(projectId: number, payload: AssetCategoryDTO) {
  return request.post<never, AssetCategoryVO>(`/projects/${projectId}/categories`, payload)
}

export function updateCategory(projectId: number, id: number, payload: AssetCategoryDTO) {
  return request.put<never, AssetCategoryVO>(`/projects/${projectId}/categories/${id}`, payload)
}

/** 删除分类：分类下有资产或子分类时后端会拒绝，提示语已足够明确 */
export function deleteCategory(projectId: number, id: number) {
  return request.delete<never, void>(`/projects/${projectId}/categories/${id}`)
}

// ==================== 资产 ====================

/** 资产列表（分页 + 多维筛选） */
export function fetchAssets(projectId: number, query: AssetQuery) {
  return request.get<never, PageResultVO<AssetVO>>(`/projects/${projectId}/assets`, { params: query })
}

export function fetchAsset(id: number) {
  return request.get<never, AssetVO>(`/assets/${id}`)
}

export function updateAsset(id: number, payload: AssetUpdateDTO) {
  return request.put<never, AssetVO>(`/assets/${id}`, payload)
}

export function deleteAsset(id: number) {
  return request.delete<never, void>(`/assets/${id}`)
}

/**
 * 首个版本的内容来源，与后端 create 的三条来源一一对应、按优先级取第一个可用：
 *   1. `file`                —— 整包直传，适合小文件；
 *   2. `uploadId`+`totalChunks` —— 分片会话已完成，服务端合并后入库；
 *   3. 都不传（但 meta.fileMd5 有值）—— 秒传，复用项目内已有的同 MD5 文件。
 */
export interface UploadSourcePayload {
  file?: File | null
  uploadId?: string | null
  totalChunks?: number | null
}

export interface UploadRequestOptions {
  /** 整包上传进度 0~100（分片上传的进度由 useChunkUpload 侧统计，不走这里） */
  onUploadProgress?: (percent: number) => void
}

/**
 * 上传请求必须关掉超时。
 *
 * axios 实例的默认超时是 20s，那是给普通 JSON 接口定的；
 * 几百 MB 的资产整包直传在慢网络下 20s 必然超时，
 * 表现为「传到一半报请求超时」，而服务端其实还在收。
 * 分片上传不走这里（每片单独请求，单片远小于 20s），但同样在一处统一。
 */
const UPLOAD_CONFIG = { timeout: 0 } as const

/** 把 multipart 的 meta 部分包成 application/json，否则后端 @RequestPart 反序列化会 415 */
function appendMeta(form: FormData, meta: unknown) {
  form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))
}

function progressHandler(onUploadProgress?: (percent: number) => void) {
  return (event: { loaded: number; total?: number }) => {
    if (onUploadProgress && event.total) {
      onUploadProgress(Math.round((event.loaded / event.total) * 100))
    }
  }
}

/**
 * 新建资产（含首个版本）。
 *
 * 用 multipart 上传：`meta` 部分必须是 application/json，
 * 否则后端按 @RequestPart 反序列化会 415。
 * 这里把 meta 包成 Blob 并显式指定 type，正是为了带上这个 Content-Type。
 */
export function createAsset(
  projectId: number,
  meta: AssetCreateDTO,
  source: UploadSourcePayload = {},
  options: UploadRequestOptions = {}
) {
  const form = new FormData()
  appendMeta(form, meta)
  if (source.file) {
    form.append('file', source.file)
  }
  // uploadId / totalChunks 是普通表单字段（后端用 @RequestParam 接），
  // 不能塞进 meta——它们在 multipart 里属于查询串而非元数据
  const params = source.uploadId
    ? { uploadId: source.uploadId, totalChunks: source.totalChunks ?? null }
    : undefined

  return request.post<never, AssetVO>(`/projects/${projectId}/assets`, form, {
    ...UPLOAD_CONFIG,
    headers: { 'Content-Type': 'multipart/form-data' },
    params,
    onUploadProgress: progressHandler(options.onUploadProgress)
  })
}

// ==================== 版本 ====================

export function fetchVersions(assetId: number) {
  return request.get<never, AssetVersionVO[]>(`/assets/${assetId}/versions`)
}

/**
 * 上传新版本（整包直传；大文件走分片，见 utils/upload.ts）。
 *
 * `file` 缺省时走秒传：后端按 `meta.fileMd5` 复用已有文件，只新增一条版本记录。
 */
export function uploadVersion(
  assetId: number,
  meta: UploadVersionDTO,
  file?: File | null,
  options: UploadRequestOptions = {}
) {
  const form = new FormData()
  appendMeta(form, meta)
  if (file) {
    form.append('file', file)
  }
  return request.post<never, AssetVersionVO>(`/assets/${assetId}/versions`, form, {
    ...UPLOAD_CONFIG,
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: progressHandler(options.onUploadProgress)
  })
}

/**
 * 携带令牌下载，拿到 Blob 后由调用方触发保存。
 *
 * 走 axios 而不是把下载地址丢给浏览器，是因为下载接口要求
 * `Authorization: Bearer` 头，浏览器原生下载带不上。
 * 代价是文件会先进内存，对几百 MB 的资产并不理想——
 * 若要优化，应让下载接口支持一次性下载票据（文档 10.5 的
 * MinIO 预签名 URL 就是这种思路），而不是在前端绕。
 */
export function downloadVersion(assetId: number, versionId: number) {
  return request.get<never, Blob>(`/assets/${assetId}/versions/${versionId}/download`, {
    responseType: 'blob'
  })
}

/** 把指定版本设为当前版本（回滚） */
export function setCurrentVersion(assetId: number, versionId: number) {
  return request.patch<never, AssetVersionVO>(`/assets/${assetId}/versions/${versionId}/current`)
}

/** 两个版本的元数据对比 */
export function compareVersions(assetId: number, from: number, to: number) {
  return request.get<never, AssetVersionCompareVO>(`/assets/${assetId}/versions/compare`, {
    params: { from, to }
  })
}

// ==================== 状态流转 ====================

export function changeAssetStatus(assetId: number, payload: AssetStatusDTO) {
  return request.patch<never, AssetVO>(`/assets/${assetId}/status`, payload)
}

export function fetchStatusLogs(assetId: number) {
  return request.get<never, AssetStatusLogVO[]>(`/assets/${assetId}/status-logs`)
}

// ==================== 秒传与分片上传 ====================

/** 秒传检查：本项目内是否已有相同 MD5 的文件 */
export function checkMd5(payload: Md5CheckDTO) {
  return request.post<never, Md5CheckVO>('/assets/upload/check-md5', payload)
}

/** 初始化分片上传会话，返回 uploadId 与服务端分片大小 */
export function initChunkUpload(payload: ChunkInitDTO) {
  return request.post<never, ChunkInitVO>('/assets/upload/init', payload)
}

/** 上传单个分片。uploadId / index 作为普通表单字段，chunk 是二进制部分 */
export function uploadChunk(uploadId: string, index: number, chunk: Blob, signal?: AbortSignal) {
  const form = new FormData()
  form.append('uploadId', uploadId)
  form.append('index', String(index))
  form.append('chunk', chunk)
  return request.post<never, void>('/assets/upload/chunk', form, {
    ...UPLOAD_CONFIG,
    headers: { 'Content-Type': 'multipart/form-data' },
    signal
  })
}

/** 合并分片并创建版本 */
export function completeChunkUpload(payload: {
  assetId: number
  uploadId: string
  totalChunks: number
  md5?: string | null
  meta: UploadVersionDTO
}) {
  const form = new FormData()
  form.append('assetId', String(payload.assetId))
  form.append('uploadId', payload.uploadId)
  form.append('totalChunks', String(payload.totalChunks))
  if (payload.md5) {
    form.append('md5', payload.md5)
  }
  form.append('meta', new Blob([JSON.stringify(payload.meta)], { type: 'application/json' }))
  // 合并 2GB 的分片是服务端的磁盘操作，同样不能受 20s 默认超时约束
  return request.post<never, AssetVersionVO>('/assets/upload/complete', form, {
    ...UPLOAD_CONFIG,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
