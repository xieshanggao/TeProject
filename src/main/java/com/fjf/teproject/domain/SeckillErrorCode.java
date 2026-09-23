package com.fjf.teproject.domain;

/**
 * 秒杀接口的错误码，以及每个错误码对应的 HTTP 状态与提示文案。
 *
 * <p>这是接口契约（docs/api/seckill.md）的唯一事实来源：同一字段在
 * 成功与失败时都是字符串类型，避免调用方需要判断类型才能解析。</p>
 *
 * <p>「售罄」与「已购」共用 409，因为它们都是资源状态冲突而非权限问题；
 * 调用方依靠 code 区分二者。附带效果是抢购接口天然幂等——重复请求
 * 稳定返回 ALREADY_PURCHASED，不会造成二次扣减。</p>
 */
public enum SeckillErrorCode {

    SUCCESS("SUCCESS", 200, "抢购成功"),
    STOCK_SOLD_OUT("STOCK_SOLD_OUT", 409, "库存已售罄"),
    ALREADY_PURCHASED("ALREADY_PURCHASED", 409, "您已参与过本次秒杀"),
    SECKILL_NOT_FOUND("SECKILL_NOT_FOUND", 404, "秒杀活动不存在"),
    NOT_READY("NOT_READY", 503, "秒杀服务暂不可用"),
    INVALID_REQUEST("INVALID_REQUEST", 400, "参数校验失败");

    private final String code;
    private final int httpStatus;
    private final String message;

    SeckillErrorCode(String code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
