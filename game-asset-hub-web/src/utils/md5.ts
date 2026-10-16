/**
 * 增量 MD5。
 *
 * 为什么自己写而不是引 spark-md5：本项目的依赖清单是固定的
 * （vue / element-plus / pinia / axios / vue-router），
 * 为了一个 90 行的算法多加一个运行时依赖并不划算。
 *
 * 为什么必须在浏览器侧算：秒传的全部价值在于「算出指纹 → 问服务端有没有 →
 * 有就别传了」。若等文件传到服务端再算，几百 MB 已经传完了，
 * 省下的只有磁盘空间，最贵的带宽一分没省。
 *
 * 为什么是「增量」接口：UE5 资产动辄 2GB，`file.arrayBuffer()` 会一次性
 * 把它读进内存并让页面直接崩掉。这里按 4MB 切片逐块喂进来，
 * 内存占用恒定，且能和上传进度条共用同一套切片循环。
 *
 * 实现为标准 MD5（RFC 1321），输出 32 位小写十六进制，
 * 与服务端 `Md5Util` / MySQL 里存的 `file_md5` 完全一致。
 */

/** 每轮的左移位数 */
const SHIFTS = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
]

/** K[i] = floor(2^32 × |sin(i+1)|)，预计算成常量表，避免运行时依赖浮点 sin */
const K = [
  0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
  0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
  0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
  0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
  0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
  0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
  0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
  0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
  0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
  0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
  0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
  0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
  0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
  0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
  0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
  0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391
]

/** 循环左移（32 位） */
function rotateLeft(value: number, bits: number): number {
  return (value << bits) | (value >>> (32 - bits))
}

/** 增量 MD5 计算器 */
export class Md5 {
  private a = 0x67452301
  private b = 0xefcdab89
  private c = 0x98badcfe
  private d = 0x10325476

  /** 未满一个 512 位块的残留字节 */
  private readonly buffer = new Uint8Array(64)
  private bufferLength = 0

  /** 累计处理的字节数，最终用于填充长度（以 bit 计） */
  private processedBytes = 0

  private finished = false

  /** 喂入一段数据，长度任意；内部自动按 64 字节切块 */
  update(data: Uint8Array): this {
    if (this.finished) {
      throw new Error('MD5 计算已完成，不能再追加数据')
    }
    let offset = 0
    this.processedBytes += data.length

    // 1. 先把上次残留的不足一个块补齐
    if (this.bufferLength > 0) {
      const need = 64 - this.bufferLength
      const take = Math.min(need, data.length)
      this.buffer.set(data.subarray(0, take), this.bufferLength)
      this.bufferLength += take
      offset = take
      if (this.bufferLength === 64) {
        this.processBlock(this.buffer, 0)
        this.bufferLength = 0
      }
    }

    // 2. 整块直接处理，不经过残留缓冲
    while (offset + 64 <= data.length) {
      this.processBlock(data, offset)
      offset += 64
    }

    // 3. 剩下的留着下次
    if (offset < data.length) {
      this.buffer.set(data.subarray(offset), 0)
      this.bufferLength = data.length - offset
    }
    return this
  }

  /** 结束计算并返回 32 位小写十六进制摘要；重复调用返回同一结果 */
  hex(): string {
    if (!this.finished) {
      this.pad()
      this.finished = true
    }
    return toHex(this.a) + toHex(this.b) + toHex(this.c) + toHex(this.d)
  }

  /** 补齐与长度字段：0x80 + 若干 0，使长度 ≡ 56 (mod 64)，再写 8 字节小端 bit 数 */
  private pad(): void {
    const bitLengthLow = (this.processedBytes * 8) >>> 0
    // JS 的 number 能精确表示到 2^53，2GB 文件的 bit 数（2^34）远在上限内，
    // 但仍按规范拆成高低两个 32 位字，避免以后放宽体积上限时出错
    const bitLengthHigh = Math.floor((this.processedBytes * 8) / 0x100000000) >>> 0

    const paddingLength = this.bufferLength < 56 ? 56 - this.bufferLength : 120 - this.bufferLength
    const padding = new Uint8Array(paddingLength + 8)
    padding[0] = 0x80
    writeUint32LE(padding, paddingLength, bitLengthLow)
    writeUint32LE(padding, paddingLength + 4, bitLengthHigh)
    this.update(padding)
  }

  /** 处理一个 64 字节块 */
  private processBlock(data: Uint8Array, offset: number): void {
    const m = new Uint32Array(16)
    for (let i = 0; i < 16; i++) {
      m[i] = readUint32LE(data, offset + i * 4)
    }

    let a = this.a
    let b = this.b
    let c = this.c
    let d = this.d

    for (let i = 0; i < 64; i++) {
      let f: number
      let g: number
      if (i < 16) {
        f = (b & c) | (~b & d)
        g = i
      } else if (i < 32) {
        f = (d & b) | (~d & c)
        g = (5 * i + 1) % 16
      } else if (i < 48) {
        f = b ^ c ^ d
        g = (3 * i + 5) % 16
      } else {
        f = c ^ (b | ~d)
        g = (7 * i) % 16
      }

      // >>> 0 把结果拉回无符号 32 位：JS 的位运算是带符号的，
      // 不归一会让后续的加法变成负数并算错
      f = (f + a + K[i] + m[g]) >>> 0
      a = d
      d = c
      c = b
      b = (b + rotateLeft(f, SHIFTS[i])) >>> 0
    }

    this.a = (this.a + a) >>> 0
    this.b = (this.b + b) >>> 0
    this.c = (this.c + c) >>> 0
    this.d = (this.d + d) >>> 0
  }
}

/** 一次性计算一段数据的 MD5（小数据量场景用） */
export function md5Hex(data: Uint8Array): string {
  return new Md5().update(data).hex()
}

/** 小端读取 32 位无符号整数 */
function readUint32LE(data: Uint8Array, offset: number): number {
  return (
    (data[offset] |
      (data[offset + 1] << 8) |
      (data[offset + 2] << 16) |
      (data[offset + 3] << 24)) >>>
    0
  )
}

/** 小端写入 32 位无符号整数 */
function writeUint32LE(target: Uint8Array, offset: number, value: number): void {
  target[offset] = value & 0xff
  target[offset + 1] = (value >>> 8) & 0xff
  target[offset + 2] = (value >>> 16) & 0xff
  target[offset + 3] = (value >>> 24) & 0xff
}

/** 32 位值 → 8 位小写十六进制（MD5 摘要按小端输出，等价于逐字节十六进制） */
function toHex(value: number): string {
  let hex = ''
  for (let i = 0; i < 4; i++) {
    hex += ((value >>> (i * 8)) & 0xff).toString(16).padStart(2, '0')
  }
  return hex
}
