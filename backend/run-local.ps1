# ============================================================
# SmartQA 本地启动脚本
# 功能: 加载 backend/.env 中的环境变量 -> 启动 Spring Boot
# 用法: 在 backend/ 目录执行  .\run-local.ps1
#      首次运行若被策略拦截: powershell -ExecutionPolicy Bypass -File .\run-local.ps1
# ============================================================
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path $envFile)) {
    Write-Host "[run-local] 未找到 .env 文件。请先复制 .env.example 为 .env 并填入真实 Key。" -ForegroundColor Yellow
    exit 1
}

# 逐行解析 .env: 跳过注释与空行, 去除 CR, 形如 KEY=VALUE
$loaded = 0
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim().TrimEnd("`r")
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $key, $value = $line.Split('=', 2)
        [Environment]::SetEnvironmentVariable($key.Trim(), $value.Trim(), 'Process')
        $loaded++
    }
}
Write-Host "[run-local] 已从 .env 加载 $loaded 个环境变量" -ForegroundColor Green
Write-Host "[run-local] AI_BASE_URL=$env:AI_BASE_URL" -ForegroundColor DarkGray
Write-Host "[run-local] AI_CHAT_MODEL=$env:AI_CHAT_MODEL" -ForegroundColor DarkGray
if ($env:AI_API_KEY) { Write-Host "[run-local] AI_API_KEY: (已设置, 不回显)" -ForegroundColor DarkGray }

# 激活 local profile: 若已复制 application-local.yml.example 为 application-local.yml,
# 则其中的本地私有配置(含 MyBatis SQL 日志 StdOutImpl)生效; 文件不存在时 Spring Boot 自动忽略, 不报错。
Write-Host "[run-local] 激活 profile: local" -ForegroundColor Green
mvn spring-boot:run -DskipTests "-Dspring-boot.run.profiles=local"
