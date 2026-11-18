CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(12) NOT NULL CHECK (role IN ('ADMIN', 'OPERATOR')),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    barcode VARCHAR(80),
    category VARCHAR(80),
    specification VARCHAR(100),
    unit VARCHAR(16) NOT NULL,
    image_url VARCHAR(512),
    warning_level INT CHECK (warning_level >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(500)
);

CREATE TABLE shelves (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    layer_count INT NOT NULL CHECK (layer_count BETWEEN 1 AND 100)
);

CREATE TABLE shelf_slots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shelf_id BIGINT NOT NULL,
    code VARCHAR(36) NOT NULL UNIQUE,
    layer_no INT NOT NULL,
    product_id BIGINT,
    qr_token VARCHAR(36) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (shelf_id, layer_no),
    FOREIGN KEY (shelf_id) REFERENCES shelves(id),
    FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE TABLE inventory (
    slot_id BIGINT PRIMARY KEY,
    quantity INT NOT NULL CHECK (quantity >= 0),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (slot_id) REFERENCES shelf_slots(id)
);

CREATE TABLE stock_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    UNIQUE (operator_id, request_id),
    FOREIGN KEY (operator_id) REFERENCES users(id)
);

CREATE TABLE stock_transfers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL UNIQUE,
    transfer_no VARCHAR(39) NOT NULL UNIQUE,
    product_id BIGINT NOT NULL,
    source_slot_id BIGINT NOT NULL,
    target_slot_id BIGINT NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    operator_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'COMPLETED',
    created_at BIGINT NOT NULL,
    CHECK (source_slot_id <> target_slot_id),
    FOREIGN KEY (request_id) REFERENCES stock_requests(id),
    FOREIGN KEY (product_id) REFERENCES products(id),
    FOREIGN KEY (source_slot_id) REFERENCES shelf_slots(id),
    FOREIGN KEY (target_slot_id) REFERENCES shelf_slots(id),
    FOREIGN KEY (operator_id) REFERENCES users(id)
);

CREATE TABLE stock_movements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    transfer_id BIGINT,
    product_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('IN', 'OUT', 'TRANSFER_IN', 'TRANSFER_OUT')),
    quantity INT NOT NULL CHECK (quantity > 0),
    before_qty INT NOT NULL CHECK (before_qty >= 0),
    after_qty INT NOT NULL CHECK (after_qty >= 0),
    operator_id BIGINT NOT NULL,
    document_no VARCHAR(80) NOT NULL,
    note VARCHAR(500),
    created_at BIGINT NOT NULL,
    UNIQUE (request_id, type),
    FOREIGN KEY (request_id) REFERENCES stock_requests(id),
    FOREIGN KEY (transfer_id) REFERENCES stock_transfers(id),
    FOREIGN KEY (product_id) REFERENCES products(id),
    FOREIGN KEY (slot_id) REFERENCES shelf_slots(id),
    FOREIGN KEY (operator_id) REFERENCES users(id)
);
CREATE INDEX movement_by_date ON stock_movements(created_at, id);
CREATE INDEX movement_by_product ON stock_movements(product_id, created_at);
CREATE INDEX movement_by_slot ON stock_movements(slot_id, created_at);
CREATE INDEX movement_by_operator ON stock_movements(operator_id, created_at);

CREATE TABLE audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    target_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (operator_id) REFERENCES users(id)
);
