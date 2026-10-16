<#
  一键启动「游戏资产协作」项目（便携版，幂等，可重复执行）

  用法：
      双击 start-all.cmd
  或  powershell -ExecutionPolicy Bypass -File <本文件>

  与旧版的区别——**整个文件夹可以随便挪位置**：
      旧版把 D:\java 写死在脚本里；本版所有路径都从脚本自身位置推导，
      拷到 U 盘、挪到别的盘符、换一台重装过的机器，双击 start-all.cmd 就能跑。

  依赖全部随包携带（在 .tools 下），不需要预先装任何东西：
      .tools\jdk     Eclipse Temurin JDK 17
      .tools\node    Node.js 24
      .tools\mysql   MySQL 8.0（便携模式，数据目录在 .tools\run\mysql\data）
      .tools\redis   Redis 5.0.14.1 for Windows
      .tools\maven   Apache Maven（重新打包时用）

  行为：
    1. 把便携 jdk / node / mysql 加进 PATH（只影响本脚本及其子进程，不动系统环境）
    2. 起便携 Redis（6379）
    3. 起便携 MySQL（首次运行会初始化数据目录并导入业务库），端口 3307
       —— 刻意避开 3306，和机器上可能已有的系统 MySQL 互不干扰
    4. 检查后端端口（8082），已在运行的跳过，未运行的启动 jar
    5. 检查前端端口（5175），已在运行的跳过，未运行的 npm run dev

  每个服务都开独立窗口，方便看日志，也方便单独关掉。
  一键停止请用同目录的 stop-all.cmd。

  注意：本文件必须保存为「UTF-8 带 BOM」。PowerShell 5.1 对无 BOM 的文件
  会按系统 ANSI(GBK) 解析，中文注释会让语法解析失败。
#>

param(
    # 只确保 Redis 在跑（供 .tools\start-redis.cmd 复用）
    [switch]$RedisOnly,
    # 强制重建便携数据库（DROP + 重新导入业务库）
    [switch]$ReinitDb
)

$ErrorActionPreference = 'Continue'

. "$PSScriptRoot\.tools\common.ps1"

# 端口不在这里另起变量：PowerShell 变量名大小写不敏感，
# $MySqlPort 和 common.ps1 里的 $MysqlPort 其实是同一个变量，重复定义只会互相打架。
# 便携 MySQL 用 3307（与系统 MySQL 的 3306 错开），Redis 用 6379，都由 common.ps1 给出。

function Write-Step {
    param([string]$Text, [string]$Color = 'Cyan')
    Write-Host ''
    Write-Host $Text -ForegroundColor $Color
}

function Write-Ok   { param([string]$T) Write-Host "  [OK] $T" -ForegroundColor Green }
function Write-Skip { param([string]$T) Write-Host "  [跳过] $T" -ForegroundColor DarkGray }
function Write-Warn { param([string]$T) Write-Host "  [!!] $T" -ForegroundColor Red }
function Write-Info { param([string]$T) Write-Host "  [--] $T" -ForegroundColor Yellow }

function ConvertTo-IniPath {
    <#  写进 MySQL 配置文件（my.ini）的路径必须用正斜杠。
        踩过的坑：MySQL 的选项文件解析器把 \t \r \n 当成转义序列，
        于是 datadir="D:\java\.tools\run\mysql\data" 里的 \r 变成回车、
        \t 变成制表符，mysqld 直接报
        "Failed to set datadir to 'D:\java\.tools<CR>un\mysql\data\'" 然后退出。
        命令行参数不经过这层解析，所以只有写进 ini 的路径要换。 #>
    param([string]$Path)
    return ($Path -replace '\\', '/')
}

function Test-MysqlReady {
    <#  探测便携 MySQL 能否真正接受查询（端口通 != 初始化完了）。

        必须分两种情况，否则会踩坑：
          * 数据目录是本次新建的 —— root 还没有密码，要用 --skip-password
          * 数据目录是复用的（重启、换机器拷过来）—— root 已有密码，必须带密码连

        最初只写了 --skip-password，于是只要不是「首次运行」，探测就永远
        Access denied，被误判成「MySQL 起不来」，连带后端连不上库启动失败。
        密码通过 MYSQL_PWD 环境变量传，不出现在命令行里。 #>
    param([switch]$SkipPassword)

    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'

    if ($SkipPassword) {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
        & $MysqlExe '--user=root' '--host=127.0.0.1' "--port=$MysqlPort" '--skip-password' '-e' 'SELECT 1;' *> $null
    } else {
        $env:MYSQL_PWD = $MysqlPassword
        & $MysqlExe '--user=root' '--host=127.0.0.1' "--port=$MysqlPort" '-e' 'SELECT 1;' *> $null
    }

    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return ($code -eq 0)
}

# ---------------------------------------------------------------
# 0. 把随包携带的工具挂进 PATH
# ---------------------------------------------------------------
Write-Step '===== 0. 装载随包工具链 ====='

$jdkBin   = Join-Path $ToolsDir 'jdk\bin'
$nodeDir  = Join-Path $ToolsDir 'node'
$mysqlBin = Join-Path $ToolsDir 'mysql\bin'
$redisDir = Join-Path $ToolsDir 'redis'

if (Test-Path (Join-Path $jdkBin 'java.exe')) {
    $env:JAVA_HOME = Split-Path $jdkBin -Parent
    $env:PATH = "$jdkBin;$nodeDir;$mysqlBin;$redisDir;$env:PATH"
    Write-Ok "JDK      $((& (Join-Path $jdkBin 'java.exe') -version 2>&1 | Select-Object -First 1))"
} else {
    Write-Warn '找不到 .tools\jdk，将回退到系统已安装的 java'
}

if (Test-Path (Join-Path $nodeDir 'node.exe')) {
    $env:PATH = "$nodeDir;$env:PATH"
    Write-Ok "Node.js  $(& (Join-Path $nodeDir 'node.exe') -v)"
} else {
    Write-Warn '找不到 .tools\node，将回退到系统已安装的 node'
}

# 后端读这些环境变量（项目的 application*.yml 留了占位符，见 dev 配置）
# 这些变量由本进程设置，Start-Process 起的子进程会继承，不必逐个传。
$env:DB_HOST     = '127.0.0.1'
$env:DB_PORT     = "$MySqlPort"
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '123456'
$env:REDIS_HOST  = '127.0.0.1'
$env:REDIS_PORT  = "$RedisPort"

# 文件落盘位置也要跟着工程目录走，否则换台机器就写不进去了。
$env:STORAGE_PATH        = Join-Path $UploadsDir 'game-asset-hub'
$env:CHUNK_TEMP_PATH     = Join-Path $UploadsDir 'game-asset-hub\tmp\chunks'

# 让子进程里的验证脚本也能定位到工程根
$env:JAVA_ROOT = $RootDir

# ---------------------------------------------------------------
# 1. 便携 MySQL
# ---------------------------------------------------------------
$mysqlBase = Join-Path $ToolsDir 'mysql'
$mysqlRun  = Join-Path $RunDir 'mysql'
$mysqlData = Join-Path $mysqlRun 'data'
# $MysqldExe / $MysqlExe 由 common.ps1 解析（随包优先，其次系统安装），此处不再另起变量
$hasPortableMysql = (Test-Path $MysqldExe) -and (Test-Path $MysqlExe) -and
                    ($MysqldExe -like "$ToolsDir*")

function Start-PortableMySql {
    # 已经有人在监听就直接返回
    if (Test-Port $MySqlPort) { return $true }

    if (-not $hasPortableMysql) { return $false }

    New-Item -ItemType Directory -Force $mysqlRun | Out-Null

    $confPath = Join-Path $mysqlRun 'my.ini'
    $errLog   = Join-Path $mysqlRun 'mysql-error.log'

    # 每次启动都重写配置：里面的路径全部由当前工程根推导，挪了位置也正确。
    # 路径一律转成正斜杠——见 ConvertTo-IniPath 的说明。
    $conf = @"
[mysqld]
basedir="$(ConvertTo-IniPath $mysqlBase)"
datadir="$(ConvertTo-IniPath $mysqlData)"
port=$MySqlPort
bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_general_ci
default-time-zone=+08:00
max_connections=200
log-error="$(ConvertTo-IniPath $errLog)"
pid-file="$(ConvertTo-IniPath (Join-Path $mysqlRun 'mysqld.pid'))"

[client]
port=$MySqlPort
default-character-set=utf8mb4
"@
    [System.IO.File]::WriteAllText($confPath, $conf, (New-Object System.Text.UTF8Encoding($false)))

    # 首次运行：初始化数据目录（--initialize-insecure 建一个空密码的 root）
    $freshInit = $false
    if (-not (Test-Path (Join-Path $mysqlData 'mysql'))) {
        Write-Info '首次运行，正在初始化便携 MySQL 数据目录（约 20-40 秒）...'
        New-Item -ItemType Directory -Force $mysqlData | Out-Null

        # --no-defaults 必须放第一位，否则会读到系统那份 my.ini
        & $mysqldExe '--no-defaults' '--initialize-insecure' "--basedir=$mysqlBase" "--datadir=$mysqlData"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $mysqlData 'mysql'))) {
            Write-Warn "MySQL 初始化失败，请查看 $errLog"
            return $false
        }
        $freshInit = $true
        Write-Ok 'MySQL 数据目录初始化完成'
    }

    Write-Info "正在启动便携 MySQL (端口 $MySqlPort)..."
    $proc = Start-Process -FilePath $mysqldExe -ArgumentList "--defaults-file=$confPath" `
                          -WorkingDirectory $mysqlBase -WindowStyle Hidden -PassThru
    if ($proc) { $proc.Id | Out-File (Join-Path $mysqlRun 'mysqld.pid.wrapper') -Encoding ascii }

    $ready = $false
    $needSetPassword = $freshInit
    for ($i = 0; $i -lt 120; $i++) {
        if (Test-MysqlReady) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready -and (Test-MysqlReady -SkipPassword)) {
        # 退一步：数据目录已经建好，但上一次没来得及设密码（比如中途被强杀）
        $ready = $true
        $needSetPassword = $true
    }
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue

    if (-not $ready) {
        Write-Warn "便携 MySQL 启动后仍无法连接，请查看 $errLog"
        return $false
    }

    # root 还是空密码时，设成与项目约定一致的密码。
    # 额外建一个 root@127.0.0.1：JDBC 走 TCP，不依赖反向 DNS 解析成 localhost。
    if ($needSetPassword) {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
        $setup = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$MysqlPassword';" +
                 "CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$MysqlPassword';" +
                 "GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;" +
                 "FLUSH PRIVILEGES;"
        & $MysqlExe '--user=root' '--host=127.0.0.1' "--port=$MysqlPort" '--skip-password' '-e' $setup *> $null
        if ($LASTEXITCODE -ne 0) {
            Write-Warn 'MySQL root 密码设置失败'
            return $false
        }
        Write-Ok "MySQL root 密码已设为 $MysqlPassword"
    }

    Write-Ok "便携 MySQL 已启动 (127.0.0.1:$MySqlPort)"
    return $true
}

# ---------------------------------------------------------------
# 2. 便携 Redis
# ---------------------------------------------------------------
function Start-PortableRedis {
    if (Test-Port $RedisPort) { return $true }

    $redisServer = Join-Path $redisDir 'redis-server.exe'
    if (-not (Test-Path $redisServer)) {
        Write-Warn "找不到 $redisServer"
        return $false
    }

    New-Item -ItemType Directory -Force $RunDir | Out-Null
    $confPath = Join-Path $RunDir 'redis.local.conf'

    # 同样每次重写：dir / logfile 是绝对路径，必须跟着工程目录走
    $conf = @"
port $RedisPort
bind 127.0.0.1
maxmemory 256mb
maxmemory-policy allkeys-lru
save 900 1
appendonly no
logfile "$(ConvertTo-IniPath (Join-Path $RunDir 'redis.log'))"
dir "$(ConvertTo-IniPath $RunDir)"
"@
    [System.IO.File]::WriteAllText($confPath, $conf, (New-Object System.Text.UTF8Encoding($false)))

    Start-Process -FilePath $redisServer -ArgumentList $confPath `
                  -WorkingDirectory $redisDir -WindowStyle Hidden | Out-Null

    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Port $RedisPort) {
            Write-Ok "便携 Redis 已启动 (127.0.0.1:$RedisPort)"
            return $true
        }
    }
    Write-Warn 'Redis 启动超时'
    return $false
}

# ---------------------------------------------------------------
# 3. 基础设施
# ---------------------------------------------------------------
Write-Step '===== 1. 检查基础设施 ====='

# 先起 Redis：init-db.ps1 建完库要清派生缓存，那时 Redis 必须已经在跑，
# 否则会打印「Redis 未运行，跳过清理」的告警（首次建库无害，重建库时就有害了）。
if (Start-PortableRedis) { } else { Write-Warn 'Redis 不可用，后端启动后会报连接失败' }

if (Start-PortableMySql) {
    if ($ReinitDb) {
        Write-Info '按参数要求重建业务库...'
        $env:MYSQL_PWD = '123456'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ToolsDir 'init-db.ps1')
    } elseif (-not (Test-Path (Join-Path $mysqlRun '.initialized'))) {
        Write-Info '便携数据库还没有业务库，正在建库并导入演示数据...'
        $env:MYSQL_PWD = '123456'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ToolsDir 'init-db.ps1')
        if ($LASTEXITCODE -eq 0) {
            New-Item -ItemType File -Force (Join-Path $mysqlRun '.initialized') | Out-Null
        }
    } else {
        Write-Ok '业务库已就绪（需要重建请运行 start-all.cmd --reinit-db）'
    }
} else {
    Write-Warn '便携 MySQL 不可用，后端将无法连接数据库'
}

if ($RedisOnly) {
    Write-Host ''
    Write-Host '  （-RedisOnly：只确保 Redis，就此结束）' -ForegroundColor DarkGray
    exit 0
}

# ---------------------------------------------------------------
# 4. 后端（已运行则跳过）
# ---------------------------------------------------------------
Write-Step '===== 2. 启动后端 ====='

$backends = @(
    @{ Name = '游戏资产协作'; Dir = 'game-asset-hub'; Jar = 'game-asset-hub-1.0.0.jar'; Port = 8082 }
)

$pidDir = Join-Path $RunDir 'pids'
New-Item -ItemType Directory -Force $pidDir | Out-Null

foreach ($b in $backends) {
    if (Test-Port $b.Port) {
        Write-Skip ("{0}  端口 {1} 已在运行" -f $b.Name, $b.Port)
        continue
    }

    $jarPath = Join-Path $RootDir "$($b.Dir)\target\$($b.Jar)"
    if (-not (Test-Path $jarPath)) {
        Write-Warn ("{0} 找不到 jar：{1}" -f $b.Name, $jarPath)
        Write-Host ("       请先打包：cd {0} && .\mvnw.cmd clean package -DskipTests" -f (Join-Path $RootDir $b.Dir)) -ForegroundColor Yellow
        continue
    }

    $javaExe = if (Test-Path (Join-Path $jdkBin 'java.exe')) { Join-Path $jdkBin 'java.exe' } else { 'java' }
    $proc = Start-Process -FilePath $javaExe `
        -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $jarPath) `
        -WorkingDirectory (Join-Path $RootDir $b.Dir) -PassThru
    if ($proc) { $proc.Id | Out-File (Join-Path $pidDir "$($b.Dir).pid") -Encoding ascii }

    Write-Ok ("{0}  端口 {1}（PID {2}）" -f $b.Name, $b.Port, $proc.Id)
}

Write-Host '  等待后端就绪...' -ForegroundColor DarkGray
foreach ($b in $backends) {
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-Port $b.Port) { break }
        Start-Sleep -Seconds 1
    }
    $ok = Test-Port $b.Port
    $mark = if ($ok) { '[OK]' } else { '[!!]' }
    $color = if ($ok) { 'DarkGray' } else { 'Red' }
    Write-Host ("    {0} {1}:{2}" -f $mark, $b.Name, $b.Port) -ForegroundColor $color
}

# ---------------------------------------------------------------
# 5. 前端（已运行则跳过）
# ---------------------------------------------------------------
Write-Step '===== 3. 启动前端 ====='

$frontends = @(
    @{ Name = '游戏资产协作'; Dir = 'game-asset-hub-web'; Port = 5175 }
)

$npmCmd = if (Test-Path (Join-Path $nodeDir 'npm.cmd')) { Join-Path $nodeDir 'npm.cmd' } else { 'npm.cmd' }

foreach ($f in $frontends) {
    if (Test-Port $f.Port) {
        Write-Skip ("{0}  端口 {1} 已在运行" -f $f.Name, $f.Port)
        continue
    }

    $dir = Join-Path $RootDir $f.Dir
    if (-not (Test-Path (Join-Path $dir 'package.json'))) {
        Write-Warn ("{0} 找不到 package.json：{1}" -f $f.Name, $dir)
        continue
    }

    if (-not (Test-Path (Join-Path $dir 'node_modules'))) {
        Write-Info ("{0} 未安装依赖，正在 npm install ..." -f $f.Name)
        Push-Location $dir
        & $npmCmd install --no-audit --no-fund
        Pop-Location
    }

    # npm 在 Windows 上是批处理 npm.cmd，Start-Process 必须用它的真实路径
    $proc = Start-Process -FilePath $npmCmd -ArgumentList @('run', 'dev') -WorkingDirectory $dir -PassThru
    if ($proc) { $proc.Id | Out-File (Join-Path $pidDir "$($f.Dir).pid") -Encoding ascii }

    Write-Ok ("{0}  端口 {1}（PID {2}）" -f $f.Name, $f.Port, $proc.Id)
}

Write-Host '  等待前端就绪...' -ForegroundColor DarkGray
foreach ($f in $frontends) {
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-Port $f.Port) { break }
        Start-Sleep -Seconds 1
    }
    $ok = Test-Port $f.Port
    $mark = if ($ok) { '[OK]' } else { '[!!]' }
    $color = if ($ok) { 'DarkGray' } else { 'Red' }
    Write-Host ("    {0} {1}:{2}" -f $mark, $f.Name, $f.Port) -ForegroundColor $color
}

# ---------------------------------------------------------------
# 6. 汇总
# ---------------------------------------------------------------
Write-Step '===== 访问地址与演示账号 =====' 'Green'

$summary = @(
    @{ Name = '游戏项目资产与协作管理系统'; Url = 'http://localhost:5175'; Accounts = 'zhangchi / 123456 (OWNER)　liyang / 123456 (ARTIST)' }
)

foreach ($s in $summary) {
    Write-Host ("  {0}" -f $s.Name) -ForegroundColor White
    Write-Host ("      地址：{0}" -f $s.Url)
    Write-Host ("      账号：{0}" -f $s.Accounts)
}

Write-Step ''
Write-Host '  一键停止：双击同目录的 stop-all.cmd' -ForegroundColor DarkGray
Write-Host '  各服务的实时日志就在它们各自的窗口里。' -ForegroundColor DarkGray
Write-Host ("  便携数据库数据目录：{0}" -f $mysqlData) -ForegroundColor DarkGray
