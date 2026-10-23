-- Keeps vehicle_status in step with vehicle on insert/update/delete, so the batch job only has to
-- handle the passage of time. Postgres version; the DB2 version is still to be written.

CREATE OR REPLACE FUNCTION vehicle_status_sync() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM vehicle_status WHERE vehicle_id = OLD.vehicle_id;
        RETURN OLD;
    END IF;

    -- valid through the expiry date; no date means nothing to expire
    UPDATE vehicle_status
       SET inspection_valid_fl = CASE WHEN NEW.inspection_expiry_dt IS NULL OR NEW.inspection_expiry_dt >= CURRENT_DATE THEN 'Y' ELSE 'N' END,
           licence_valid_fl    = CASE WHEN NEW.licence_expiry_dt    IS NULL OR NEW.licence_expiry_dt    >= CURRENT_DATE THEN 'Y' ELSE 'N' END
     WHERE vehicle_id = NEW.vehicle_id;

    IF NOT FOUND THEN
        INSERT INTO vehicle_status (vehicle_id, inspection_valid_fl, licence_valid_fl)
        VALUES (NEW.vehicle_id,
                CASE WHEN NEW.inspection_expiry_dt IS NULL OR NEW.inspection_expiry_dt >= CURRENT_DATE THEN 'Y' ELSE 'N' END,
                CASE WHEN NEW.licence_expiry_dt    IS NULL OR NEW.licence_expiry_dt    >= CURRENT_DATE THEN 'Y' ELSE 'N' END);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- UPDATE OF limits firing to changes in the columns that matter on this busy legacy table
CREATE TRIGGER vehicle_status_sync_trg
    AFTER INSERT OR DELETE OR UPDATE OF inspection_expiry_dt, licence_expiry_dt ON vehicle
    FOR EACH ROW EXECUTE FUNCTION vehicle_status_sync();
