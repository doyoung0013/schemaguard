CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(100));
CREATE TABLE stocks (
    id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    quantity INT NOT NULL
);
CREATE INDEX idx_stocks_product_status ON stocks (product_id, status);
CREATE INDEX idx_stocks_status ON stocks (status);
