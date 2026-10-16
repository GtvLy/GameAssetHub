import { completeChunkUpload, initChunkUpload, uploadChunk } from '@/api/asset'
import { DEFAULT_CHUNK_SIZE, MAX_UPLOAD_SIZE } from '@/constants/asset'
import { Md5 } from '@/utils/md5'
import type { AssetVersionVO, UploadVersionDTO } from '@/types/asset'

/**
 * 大文件分片上传（文档 7.6）。
 *
 * 完整链路：**算指纹 → 秒传检查 → 切片逐片上传 → 合并**。
 *
 * 关于「为什么先算指纹」：秒传的全部价值在于「有就别传」。
 * 若先传完再让服务端算 MD5，几百 MB 已经占用了带宽，只省下磁盘，
 * 而带宽才是美术最直观的痛点。
 *
 * 关于「为什么按片读文件算 MD5」：`file.arrayBuffer()` 会把 2GB 的资产
 * 一次性读进内存，页面会直接崩。这里按 4MB 切片流式喂给增量 MD5，
 * 内存占用是常数级。
 */

/** 计算指纹时的读取切片大小。比上传分片小，让进度条更顺滑 */
const HASH_SLICE = 4 * 1024 * 1024

/** 上传阶段，用于前端展示当前在做什么 */
export type UploadPhase = 'hashing' | 'checking' | 'uploading' | 'merging'

export interface UploadProgress {
  phase: UploadPhase
  /** 整体百分比 0~100 */
  percent: number
  uploadedBytes: number
  totalBytes: number
  /** 已上传分片数 / 总片数（上传阶段才有意义） */
  currentChunk: number
  totalChunks: number
  /** 速度文案，如「12.3 MB/s」；样本不足时为空 */
  speedText: string
}

export interface UploadOptions {
  onProgress?: (progress: UploadProgress) => void
  /** 取消上传：中断当前分片请求并停止后续分片 */
  signal?: AbortSignal
}

/** 用户主动取消上传时抛出，调用方据此静默处理（不当作错误提示） */
export class UploadCancelledError extends Error {
  constructor() {
    super('上传已取消')
    this.name = 'UploadCancelledError'
  }
}

export interface ChunkUploadResult {
  uploadId: string
  totalChunks: number
  md5: string
  size: number
}

/** 把字节数转成人话体积，与后端 FileUtil.humanSize 的口径保持一致 */
export function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

/** 上传前的体积校验，超限直接拒绝，避免白传一遍 */
export function assertUploadable(file: File): void {
  if (!file || file.size === 0) {
    throw new Error('文件为空，无法上传')
  }
  if (file.size > MAX_UPLOAD_SIZE) {
    throw new Error(`文件超过 2GB 上限（当前 ${humanSize(file.size)}）`)
  }
}

/**
 * 流式计算文件 MD5。
 *
 * @param onProgress 0~100 的读取进度
 */
export async function computeFileMd5(
  file: File,
  onProgress?: (percent: number) => void,
  signal?: AbortSignal
): Promise<string> {
  const md5 = new Md5()
  let offset = 0
  while (offset < file.size) {
    if (signal?.aborted) {
      throw new UploadCancelledError()
    }
    const end = Math.min(offset + HASH_SLICE, file.size)
    const chunk = file.slice(offset, end)
    md5.update(new Uint8Array(await chunk.arrayBuffer()))
    offset = end
    onProgress?.(Math.round((offset / file.size) * 100))
  }
  return md5.hex()
}

/**
 * 分片上传文件内容（不创建版本记录）。
 *
 * 返回的 uploadId / totalChunks / md5 用于随后调用 complete。
 * 把「传字节」与「建版本」拆开，是为了让秒传命中时可以直接跳过前者。
 */
export async function uploadFileInChunks(
  file: File,
  md5: string,
  options: UploadOptions = {}
): Promise<ChunkUploadResult> {
  const { onProgress, signal } = options
  assertUploadable(file)

  const session = await initChunkUpload({
    fileName: file.name,
    fileSize: file.size,
    totalChunks: Math.max(1, Math.ceil(file.size / DEFAULT_CHUNK_SIZE)),
    md5
  })

  // 以服务端下发的分片大小为准：两边不一致时合并结果必然是坏的，
  // 而这种错误表现为「上传成功但模型打不开」，排查方向很容易跑偏
  const chunkSize = session.chunkSize > 0 ? session.chunkSize : DEFAULT_CHUNK_SIZE
  const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize))
  const uploaded = new Set(session.uploadedIndexes ?? [])

  let uploadedBytes = uploaded.size * chunkSize
  const startedAt = Date.now()
  const report = (chunkIndex: number) => {
    const elapsed = (Date.now() - startedAt) / 1000
    onProgress?.({
      phase: 'uploading',
      percent: Math.min(100, Math.round((uploadedBytes / file.size) * 100)),
      uploadedBytes: Math.min(uploadedBytes, file.size),
      totalBytes: file.size,
      currentChunk: chunkIndex,
      totalChunks,
      speedText: elapsed > 0.3 ? `${humanSize(uploadedBytes / elapsed)}/s` : ''
    })
  }

  report(uploaded.size)
  for (let index = 0; index < totalChunks; index++) {
    if (signal?.aborted) {
      throw new UploadCancelledError()
    }
    // 断点续传：服务端已有的分片不再重传
    if (uploaded.has(index)) {
      continue
    }
    const blob = file.slice(index * chunkSize, Math.min((index + 1) * chunkSize, file.size))
    await uploadChunk(session.uploadId, index, blob, signal)
    uploadedBytes += blob.size
    report(index + 1)
  }

  onProgress?.({
    phase: 'merging',
    percent: 100,
    uploadedBytes: file.size,
    totalBytes: file.size,
    currentChunk: totalChunks,
    totalChunks,
    speedText: ''
  })

  return { uploadId: session.uploadId, totalChunks, md5, size: file.size }
}

/** 合并分片并创建版本（走服务端标准的版本创建流程） */
export async function completeVersionUpload(
  assetId: number,
  upload: ChunkUploadResult,
  meta: UploadVersionDTO
): Promise<AssetVersionVO> {
  return completeChunkUpload({
    assetId,
    uploadId: upload.uploadId,
    totalChunks: upload.totalChunks,
    md5: upload.md5,
    meta
  })
}
