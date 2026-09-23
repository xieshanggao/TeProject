import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// ============================================================================
// 秒杀压测脚本（k6）
//
// 目标：观察 QPS，并实证「成功数不超过初始库存」。
//
// 前置条件（不满足则结果没有意义）：
//   1. 应用已在 localhost:8088 运行（mvnw.cmd spring-boot:run）。
//   2. 目标活动已预热：启动时 SeckillWarmUpRunner 用 SETNX 写入库存键。
//      若 Redis 中没有该键，Lua 返回 -3，所有请求都会拿到 503，压测结果无意义。
//   3. 目标活动的 Redis 库存处于预期初值。压测会真实消耗 Redis 库存与 MySQL 数据，
//      跑完后必须重置（重启前先删除 Redis 库存键，或清库）才能重跑，
//      否则第二轮是在「已售罄」的状态上压测。
//
// 诚实声明：本脚本从未在 k6 上运行过——开发机上没有安装 k6。
// 它只通过了 node --check 的语法校验（k6 脚本是 ESM，校验时需复制为 .mjs）。
// 任何「压测通过」的结论都必须由真正执行过的人自行得出。
//
// 默认使用活动 1（库存 100）。
// ============================================================================

const successCount = new Counter('seckill_success');
const soldOutCount = new Counter('seckill_sold_out');
const alreadyPurchasedCount = new Counter('seckill_already_purchased');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8088';
const SECKILL_ID = __ENV.SECKILL_ID || '1';
const INITIAL_STOCK = parseInt(__ENV.INITIAL_STOCK || '100', 10);

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 3000,
      stages: [
        { target: 1000, duration: '5s' },
        { target: 3000, duration: '10s' },
        { target: 0, duration: '2s' },
      ],
    },
  },
};

export default function () {
  // 每个 VU 迭代使用不同 userId，模拟不同用户；同一 userId 重复请求
  // 会被限购拦下，无法压出真实的库存竞争。
  const userId = `load_u${__VU}_${__ITER}`;
  const response = http.post(
    `${BASE_URL}/api/seckill/activities/${SECKILL_ID}/purchase`,
    JSON.stringify({ userId }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(response, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });

  if (response.body && response.body.includes('"code":"SUCCESS"')) {
    successCount.add(1);
  } else if (response.body && response.body.includes('"code":"STOCK_SOLD_OUT"')) {
    soldOutCount.add(1);
  } else if (response.body && response.body.includes('"code":"ALREADY_PURCHASED"')) {
    alreadyPurchasedCount.add(1);
  }
}

export function handleSummary(data) {
  const successes = data.metrics.seckill_success
    ? data.metrics.seckill_success.values.count : 0;
  const oversold = successes > INITIAL_STOCK;

  const verdict = oversold
    ? `超卖！成功 ${successes} 件，超过初始库存 ${INITIAL_STOCK} 件`
    : `不超卖：成功 ${successes} 件，初始库存 ${INITIAL_STOCK} 件`;

  return {
    stdout: `\n${'='.repeat(60)}\n${verdict}\n${'='.repeat(60)}\n`,
    'scripts/loadtest/last-result.json': JSON.stringify(data, null, 2),
  };
}
