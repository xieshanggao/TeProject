# 订单 API 示例

> 仅为契约示例。请以已确认的项目需求替换接口路径、字段和错误码。

## 创建订单

`POST /api/orders`

请求：

```json
{
  "items": [
    { "productId": "P1001", "quantity": 2 }
  ]
}
```

响应：`201 Created`

```json
{
  "id": "ORD-20260729-001",
  "status": "PENDING_PAYMENT",
  "amount": 398.00
}
```

## 取消订单

`POST /api/orders/{orderId}/cancel`

响应：`200 OK`

- `PENDING_PAYMENT` 订单变为 `CANCELLED`。
- `PAID` 订单变为 `REFUNDING`，并异步处理退款。

## 错误格式

```json
{
  "code": "ORDER_STATUS_INVALID",
  "message": "当前订单状态不允许取消。"
}
```

| 错误码 | 返回时机 |
| --- | --- |
| `ORDER_NOT_FOUND` | 订单 ID 不存在，或调用方无权查看。 |
| `ORDER_STATUS_INVALID` | 请求的状态流转不被允许。 |
| `VALIDATION_ERROR` | 请求数据缺失或不合法。 |
