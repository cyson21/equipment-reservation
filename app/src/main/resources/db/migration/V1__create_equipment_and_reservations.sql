CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE equipment (
    id bigint PRIMARY KEY,
    name text NOT NULL
);

INSERT INTO equipment (id, name) VALUES
    (1, 'Camera'),
    (2, 'Tripod');

CREATE TABLE reservation (
    id uuid PRIMARY KEY,
    equipment_id bigint NOT NULL REFERENCES equipment (id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CHECK (starts_at < ends_at),
    CONSTRAINT reservation_no_active_overlap EXCLUDE USING gist (
        equipment_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);
