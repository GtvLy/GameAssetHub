<#
  一键停止「游戏资产协作」项目的全部服务（便携版）

  用法：
      双击 stop-all.cmd
  或  powershell -ExecutionPolicy Bypass -File <本文件>

  停止顺序与启动相反：先前端，再后端，然后 Redis，最后便携 MySQL。
  两种定位方式结合使用，互为兜底：
    * PID 文件（.tools\run\pids\*.pid）—— 启动时记下的，最准
    * 端口占用者（8082 / 5175 / 6379 / 3307）—— PID 文件丢了也能停干净

  刻意不动系统 MySQL(3306)：那是系统服务，与本项目无关。

  注意：本文件必须保存为「UTF-8 带 BOM」。
#>

param(
    # 连同便携 MySQL 一起停（默认也停，加这个开关只是让意图更明确）
    [switch]$KeepDatabase
)

$ErrorActionPreference = 'Continue'

. "$PSScriptRoot\.tools\common.ps1"

$MySqlPort = 3307
$RedisPort = 6379
$pidDir    = Join-Path $RunDir 'pids'

function Write-Step { param([string]$T, [string]$C = 'Cyan') Write-Host ''; Write-Host $T -ForegroundColor $C }

# 端口占用者的 PID；取不到返回 $null
function Get-PortOwnerPid {
    param([int]$Port)
    $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
            Select-Object -First 1
    if ($conn) { return $conn.OwningProcess }
    return $null
}

function Stop-One {
    param([string]$Label, [int]$Port, [string]$PidFile)

    $killed = $false

    # 1) 先用 PID 文件
    if ($PidFile -and (Test-Path $PidFile)) {
        $raw = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        $procId = 0
        if ([int]::TryParse("$raw".Trim(), [ref]$procId) -and $procId -gt 0) {
            $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($p) {
                # 连同子进程一起结束：npm.cmd 会再拉起 node，只杀父进程会留下孤儿
                try {
                    & taskkill /PID $procId /T /F *> $null
                    $killed = $true
                } catch {
                    try { Stop-Process -Id $procId -Force -ErrorAction Stop; $killed = $true } catch { }
                }
            }
        }
        Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    }

    # 2) 再按端口兜底（PID 文件缺失、或进程被换过 PID 的情况）
    if (-not $killed) {
        $owner = Get-PortOwnerPid $Port
        if ($owner) {
            try {
                & taskkill /PID $owner /T /F *> $null
                $killed = $true
            } catch { }
        }
    }

    if ($killed) {
        Write-Host ("  [已停止] {0}（端口 {1}）" -f $Label, $Port) -ForegroundColor Green
    } else {
        Write-Host ("  [未运行] {0}（端口 {1}）" -f $Label, $Port) -ForegroundColor DarkGray
    }
}

Write-Step '===== 停止前端 ====='
Stop-One '游戏资产协作 前端' 5175 (Join-Path $pidDir 'game-asset-hub-web.pid')

Write-Step '===== 停止后端 ====='
Stop-One '游戏资产协作 后端' 8082 (Join-Path $pidDir 'game-asset-hub.pid')

Write-Step '===== 停止基础服务 ====='
Stop-One 'Redis' 6379 $null

if (-not $KeepDatabase) {
    Stop-One '便携 MySQL' $MySqlPort $null
} else {
    Write-Host ("  [保留] 便携 MySQL（端口 {0}）" -f $MySqlPort) -ForegroundColor DarkGray
}

Write-Step ''
Write-Host '  完毕。系统安装的 MySQL(3306) 未受影响。' -ForegroundColor DarkGray
