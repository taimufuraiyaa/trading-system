CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL
);

CREATE TABLE wallets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    balance DECIMAL(18, 8) NOT NULL DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE (user_id, currency),
    CHECK (balance >= 0)
);

CREATE TABLE aggregated_prices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_pair VARCHAR(20) NOT NULL,
    bid_price DECIMAL(18, 8) NOT NULL,
    ask_price DECIMAL(18, 8) NOT NULL,
    bid_source VARCHAR(20) NOT NULL,
    ask_source VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE trades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trading_pair VARCHAR(20) NOT NULL,
    trade_type VARCHAR(4) NOT NULL,
    price DECIMAL(18, 8) NOT NULL,
    quantity DECIMAL(18, 8) NOT NULL,
    total_amount DECIMAL(18, 8) NOT NULL,
    fee_percentage DECIMAL(5, 4) NOT NULL,
    fee_amount DECIMAL(18, 8) NOT NULL,
    net_amount DECIMAL(18, 8) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE (user_id, idempotency_key),
    CHECK (fee_amount >= 0),
    CHECK (net_amount > 0)
);

CREATE TABLE fee_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fee_percentage DECIMAL(5, 4) NOT NULL,
    effective_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (fee_percentage >= 0 AND fee_percentage < 1)
);

CREATE TABLE exchange_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange_name VARCHAR(20) NOT NULL UNIQUE,
    status VARCHAR(10) NOT NULL,
    last_success_at TIMESTAMP,
    last_failure_at TIMESTAMP,
    failure_reason VARCHAR(500),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
