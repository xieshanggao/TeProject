# 秒杀压测启动脚本（Windows / PowerShell）
#
# 前置条件：
#   1. 应用已在 localhost:8088 运行
#   2. 已安装 k6：https://k6.io/docs/get-started/installation/
#   3. 目标活动的库存已在 Redis 中重置，且活动已预热
#
# 注意：压测会真实消耗 Redis 库存与 MySQL 数据。跑完必须重置
# （删除 Redis 库存键后重启应用，或清库）才能重跑。

$ErrorActionPreference = "Stop"

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 k6，请先安装：https://k6.io/docs/get-started/installation/"
}

$SeckillId = if ($env:SECKILL_ID) { $env:SECKILL_ID } else { "1" }
$InitialStock = if ($env:INITIAL_STOCK) { $env:INITIAL_STOCK } else { "100" }
$BaseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8088" }

Write-Host "压测目标: $BaseUrl/api/seckill/activities/$SeckillId/purchase"
Write-Host "初始库存: $InitialStock"
Write-Host ""

$env:SECKILL_ID = $SeckillId
$env:INITIAL_STOCK = $InitialStock
$env:BASE_URL = $BaseUrl

k6 run --summary-trend-stats "avg,p(95),p(99),max" "$PSScriptRoot/seckill-loadtest.js"

Write-Host ""
Write-Host "压测结束。请核对上方「不超卖」结论，并检查应用日志中是否出现对账告警。"
Write-Host "注意：本脚本从未在本仓库的开发机上实际运行过（该机未安装 k6）。"
