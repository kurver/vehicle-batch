-- Keeps vehicle_status in step with vehicle on insert/update/delete, so the batch job only has to
-- handle the passage of time. Postgres version; the DB2 version is still to be written.
-- The rules mirror the domain (Vehicle#flagsOn) and ComplianceSql; keep all three in line.

CREATE OR REPLACE FUNCTION vehicle_status_sync() RETURNS trigger AS $$
DECLARE
    inspection_fl CHAR(1);
    licence_fl    CHAR(1);
    a_or_b_fl     CHAR(1);
    lapsed        CHAR(1);
    exempt        CHAR(1);
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM vehicle_status WHERE vehicle_id = OLD.vehicle_id;
        RETURN OLD;
    END IF;

    -- expiry dates: valid through the date; no date means nothing to expire
    inspection_fl := CASE WHEN NEW.inspection_expiry_dt IS NULL OR NEW.inspection_expiry_dt >= CURRENT_DATE THEN 'Y' ELSE 'N' END;
    licence_fl    := CASE WHEN NEW.licence_expiry_dt    IS NULL OR NEW.licence_expiry_dt    >= CURRENT_DATE THEN 'Y' ELSE 'N' END;
    -- usage 10/11 are the A or B farm vehicles
    a_or_b_fl     := CASE WHEN NEW.vehicle_usage_cd IN (10, 11) THEN 'Y' ELSE 'N' END;
    -- registration status 5 is lapsed
    lapsed        := CASE WHEN NEW.regt_status_cd = 5 THEN 'Y' ELSE 'N' END;
    -- exempt through the end date; no date means not exempt
    exempt        := CASE WHEN NEW.exempt_end_dt >= CURRENT_DATE THEN 'Y' ELSE 'N' END;

    UPDATE vehicle_status
       SET inspection_valid_fl = inspection_fl,
           licence_valid_fl    = licence_fl,
           a_or_b_rego_fl      = a_or_b_fl,
           lapsed_fl           = lapsed,
           exempt_fl           = exempt
     WHERE vehicle_id = NEW.vehicle_id;

    IF NOT FOUND THEN
        INSERT INTO vehicle_status (vehicle_id, inspection_valid_fl, licence_valid_fl, a_or_b_rego_fl, lapsed_fl, exempt_fl)
        VALUES (NEW.vehicle_id, inspection_fl, licence_fl, a_or_b_fl, lapsed, exempt);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- UPDATE OF limits firing to changes in the columns that matter on this busy legacy table
CREATE TRIGGER vehicle_status_sync_trg
    AFTER INSERT OR DELETE
       OR UPDATE OF inspection_expiry_dt, licence_expiry_dt, vehicle_usage_cd, regt_status_cd, exempt_end_dt
    ON vehicle
    FOR EACH ROW EXECUTE FUNCTION vehicle_status_sync();
