-- Default user
INSERT INTO users (username) VALUES ('default_user');

-- Wallets for default user: USDT 50000, ETH 0, BTC 0
INSERT INTO wallets (user_id, currency, balance) VALUES (1, 'USDT', 50000.00000000);
INSERT INTO wallets (user_id, currency, balance) VALUES (1, 'ETH', 0.00000000);
INSERT INTO wallets (user_id, currency, balance) VALUES (1, 'BTC', 0.00000000);

-- Default fee configuration: 0.1%
INSERT INTO fee_config (fee_percentage, effective_from, active) VALUES (0.0010, CURRENT_TIMESTAMP, TRUE);

-- Initial exchange status (both DOWN at startup)
INSERT INTO exchange_status (exchange_name, status, updated_at) VALUES ('binance', 'DOWN', CURRENT_TIMESTAMP);
INSERT INTO exchange_status (exchange_name, status, updated_at) VALUES ('huobi', 'DOWN', CURRENT_TIMESTAMP);
