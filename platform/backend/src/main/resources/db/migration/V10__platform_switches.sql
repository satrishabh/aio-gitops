-- On/off switches for platform features that are installed but held off until an admin switches them on
-- (e.g. the Biome lint step in CI, switched with its connector phase). No row = off.
CREATE TABLE platform_switches (
    name       VARCHAR(63)  PRIMARY KEY,
    enabled    BOOLEAN      NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    changed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
