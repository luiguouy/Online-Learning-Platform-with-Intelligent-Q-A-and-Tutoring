#Requires -Version 5.1
<#
.SYNOPSIS
    一键为「在线学习智能答疑辅导平台」构建 Graft 上下文图谱。
.DESCRIPTION
    Graft(@nanonets/graft) 已装在 WSL 全局，Windows 侧通过 graft.cmd 转发。
    本脚本自动定位仓库根目录、把 Windows 路径换算成 WSL 的 /mnt/... 路径，
    再以纯静态解析（零 Key、零费用）建图，输出到仓库根的 graft/ 目录（已 gitignore）。

    依赖：已安装并可用的 WSL + WSL 内的 graft（graft version 可验证）。
.EXAMPLE
    pwsh -File scripts/graft-build.ps1            # 建图
    pwsh -File scripts/graft-build.ps1 -Map       # 建图后顺便打印仓库地图概览
    pwsh -File scripts/graft-build.ps1 -Viz       # 建图后启动交互式可视化(本地 4400 端口)
#>
[CmdletBinding()]
param(
    [switch]$Map,   # 建图后输出 token 预算内的目录/热点概览
    [switch]$Viz,   # 建图后启动本地交互式可视化服务
    [switch]$Check  # 只做「图谱是否过时」检查（CI 用），不重建
)

$ErrorActionPreference = 'Stop'

# 本脚本位于 <repo>/scripts/，向上一级即仓库根
$RepoRoot = Split-Path -Parent $PSScriptRoot

# 换算成 WSL 路径：D:\foo\bar -> /mnt/d/foo/bar
function ConvertTo-WslPath([string]$winPath) {
    if ($winPath -notmatch '^([A-Za-z]):\\') {
        throw "无法识别的 Windows 绝对路径: $winPath"
    }
    $drive = $winPath.Substring(0, 1).ToLower()
    $rest  = $winPath.Substring(3) -replace '\\', '/'
    return "/mnt/$drive/$rest"
}

$WslRoot = ConvertTo-WslPath $RepoRoot

# 检查 WSL 与 graft 可用性
$graftVer = (wsl bash -lc 'graft version 2>/dev/null' | Out-String).Trim()
if ($LASTEXITCODE -ne -1 -and [string]::IsNullOrWhiteSpace($graftVer)) {
    Write-Warning "WSL 内未检测到 graft，请先在 WSL 执行: npm i -g @nanonets/graft"
    exit 1
}
Write-Host "graft: $graftVer  |  repo(WSL): $WslRoot" -ForegroundColor DarkGray

if ($Check) {
    wsl bash -lc "graft check '$WslRoot'"
    exit $LASTEXITCODE
}

# 核心建图：仅解析本项目实际用到的语言扩展名（纯本地，无需 Key）
Write-Host "`n== 构建 Graft 上下文图谱 ==" -ForegroundColor Cyan
wsl bash -lc "graft build '$WslRoot' -e .java -e .ts -e .vue"
if ($LASTEXITCODE -ne 0) {
    Write-Error "graft build 失败，退出码 $LASTEXITCODE"
    exit $LASTEXITCODE
}

if ($Map) {
    Write-Host "`n== 仓库地图概览 ==" -ForegroundColor Cyan
    wsl bash -lc "graft map '$WslRoot'"
}

if ($Viz) {
    Write-Host "`n== 启动可视化 (http://localhost:4400 ，Ctrl+C 停止) ==" -ForegroundColor Cyan
    wsl bash -lc "graft viz '$WslRoot'"
}

Write-Host "`n完成。图谱输出目录: $RepoRoot\graft\ （已 gitignore，勿提交）" -ForegroundColor Green
