CREATE TABLE IF NOT EXISTS seckill_activity (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(128) NOT NULL,
    initial_stock    INT          NOT NULL,
    remaining_stock  INT          NOT NULL,
    stock_updated_at DATETIME     NOT NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS seckill_record (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    seckill_id BIGINT      NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    -- 使消息的「至少一次投递」成为可放心接受的前提：重复消费时
    -- INSERT 撞唯一键，捕获 DuplicateKeyException 即可，无需额外幂等表。
    UNIQUE KEY uk_seckill_user (seckill_id, user_id)
);
