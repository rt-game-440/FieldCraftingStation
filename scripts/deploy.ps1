<#
.SYNOPSIS
  构建场域合成站 jar 并部署到本地 GTNH 实例的 mods 目录，用于运行测试。

.DESCRIPTION
  1. 运行 gradlew build（含单元测试；加 -SkipTests 跳过测试）
  2. 删除实例 mods 目录里旧的 fieldcrafting-*.jar
  3. 复制新构建的 jar（不含 -dev / -sources）到 mods 目录

.PARAMETER ModsDir
  实例 mods 目录（必填）。建议使用专用测试实例，不要指向正在游玩的实例。

.EXAMPLE
  .\scripts\deploy.ps1 -ModsDir "D:\test\instance\.minecraft\mods"
  .\scripts\deploy.ps1 -ModsDir "D:\test\instance\.minecraft\mods" -SkipTests
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$ModsDir,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path $ModsDir)) { throw "mods 目录不存在：$ModsDir" }

$tasks = @('spotlessApply', 'build')
if ($SkipTests) { $tasks += @('-x', 'test') }
& "$root\gradlew.bat" @tasks
if ($LASTEXITCODE -ne 0) { throw "构建失败（退出码 $LASTEXITCODE）" }

$jar = Get-ChildItem "$root\build\libs\fieldcrafting-*.jar" |
    Where-Object { $_.Name -notmatch '-(dev|sources)\.jar$' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw '未找到构建产物 build\libs\fieldcrafting-*.jar' }

Get-ChildItem "$ModsDir\fieldcrafting-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $jar.FullName $ModsDir
Write-Host "已部署：$($jar.Name) -> $ModsDir"
