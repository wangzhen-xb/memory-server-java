# =====================================================================
# run-local.ps1 —— Java 后端本机一键启动（依赖 Docker Desktop）
# 用法：在本目录（memory-server-java）右键"用 PowerShell 运行"，或：
#   powershell -ExecutionPolicy Bypass -File .\run-local.ps1
# 作用：起 PostgreSQL + Redis 容器 → 等数据库就绪 → 启动 Java 服务
# 端口：Java 用 8788，刻意避开仍在运行的旧 Node 服务(8787)，方便两套对比
# =====================================================================

# 用 Continue：docker/mvn 等原生命令会向 stderr 输出进度，Stop 会让 PowerShell 5.1 误抛终止错误
$ErrorActionPreference = "Continue"
# 切到脚本所在目录，保证 docker compose 能找到 docker-compose.yml
Set-Location $PSScriptRoot

Write-Host "[1/5] 检查 Docker 是否已启动..." -ForegroundColor Cyan
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "[X] 未检测到 Docker。请先安装 Docker Desktop（见 学习指南.md 第 1 步），再重跑本脚本。" -ForegroundColor Red
    exit 1
}
docker info > $null 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[X] Docker 已安装但引擎未启动。请打开 Docker Desktop，等左下角状态变绿后再重跑。" -ForegroundColor Red
    exit 1
}

Write-Host "[2/5] 启动 PostgreSQL + Redis 容器..." -ForegroundColor Cyan
docker compose up -d postgres redis
if ($LASTEXITCODE -ne 0) {
    Write-Host "[X] 容器启动失败。执行 docker compose logs postgres 查看原因（多为 5432/6379 端口被占用）。" -ForegroundColor Red
    exit 1
}

Write-Host "[3/5] 等待 PostgreSQL 就绪（首次初始化建表会稍慢）..." -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 30; $i += 1) {
    docker exec memory-postgres pg_isready -U memory -d memory_db > $null 2>&1
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Host "[X] PostgreSQL 60 秒内未就绪，请执行 docker compose logs postgres 查看原因。" -ForegroundColor Red
    exit 1
}
Write-Host "   PostgreSQL 已就绪（表结构由 01-schema.sql 自动初始化）" -ForegroundColor Green

Write-Host "[4/5] 检查 jar 包，缺失则自动打包..." -ForegroundColor Cyan
if (-not (Test-Path "target\memory-server.jar")) {
    Write-Host "   未找到 jar，开始 mvn package（约 1-2 分钟）..." -ForegroundColor Yellow
    mvn -q clean package
    if ($LASTEXITCODE -ne 0) { Write-Host "[X] 打包失败，见上方 Maven 报错" -ForegroundColor Red; exit 1 }
}

Write-Host "[5/5] 启动 Java 服务，端口 8788...（Ctrl+C 停止）" -ForegroundColor Cyan
Write-Host "   健康检查地址： http://127.0.0.1:8788/actuator/health" -ForegroundColor White
# 固定堆上限：会话同步单请求体最大 64MB，不限堆会让 JVM 一直涨到被系统 OOM Killer 直接杀掉（无日志、无退出码）。
# 1GB 约能同时容纳 6 个顶格大小的同步请求；需要调大时用环境变量 JAVA_OPTS 覆盖。
$javaOpts = $env:JAVA_OPTS
if (-not $javaOpts) { $javaOpts = "-Xms256m -Xmx1024m" }
Write-Host "   JVM 参数：$javaOpts" -ForegroundColor White
java $javaOpts.Split(" ") -jar target\memory-server.jar
