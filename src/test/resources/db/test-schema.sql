-- Stand-in for the legacy tables, which exist already in the real database.
CREATE TABLE vehicle (
    vehicle_id          INTEGER PRIMARY KEY,
    registration        VARCHAR(10),
    inspection_expiry_dt DATE,
    licence_expiry_dt    DATE
);

CREATE TABLE vehicle_status (
    vehicle_id          INTEGER PRIMARY KEY,
    inspection_valid_fl CHAR(1) NOT NULL,
    licence_valid_fl    CHAR(1) NOT NULL
);
