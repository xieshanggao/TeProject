# 内存秒杀 API 示例

> 此接口用于演示单应用实例内的高并发库存扣减，不可直接作为生产秒杀方案使用。

## 抢购

`POST /api/seckill/purchase`

每次请求固定购买 `1` 件演示商品。初始库存为 `100`。

成功响应：`200 OK`

```json
{
  "code": 0,
  "message": "抢购成功",
  "remainingStock": 99
}
```

售罄响应：`409 Conflict`

```json
{
  "code": "STOCK_SOLD_OUT",
  "message": "库存已售罄",
  "remainingStock": 0
}
```

## 查询库存

`GET /api/seckill/stock`

```json
{
  "remainingStock": 100
}
```

## 约束

- 库存保存在应用内存中，应用重启后恢复为 `100`。
- 不包含身份认证、用户限购、订单创建或支付。
- `remainingStock` 是接口处理时的库存快照；其他并发请求可能在响应返回前继续扣减库存。
