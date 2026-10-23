-- Stand-in for the legacy tables, which exist already in the real database.
CREATE TABLE vehicle (
    vehicle_id           INTEGER PRIMARY KEY,
    registration         VARCHAR(10),
    inspection_expiry_dt DATE,
    licence_expiry_dt    DATE,
    vehicle_usage_cd     SMALLINT,
    regt_status_cd       SMALLINT,
    exempt_end_dt        DATE
);

-- flags nullable, as newly added columns would be until the job or trigger fills them
CREATE TABLE vehicle_status (
    vehicle_id          INTEGER PRIMARY KEY,
    inspection_valid_fl CHAR(1),
    licence_valid_fl    CHAR(1),
    a_or_b_rego_fl      CHAR(1),
    lapsed_fl           CHAR(1),
    exempt_fl           CHAR(1)
);
