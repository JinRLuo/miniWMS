CREATE TABLE IF NOT EXISTS user_preferences (
    user_id BIGINT PRIMARY KEY,
    weight_unit VARCHAR(4) NOT NULL DEFAULT 'JIN',
    CONSTRAINT user_preferences_user_fk FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT user_weight_unit_check CHECK (weight_unit IN ('JIN', 'GRAM'))
);
