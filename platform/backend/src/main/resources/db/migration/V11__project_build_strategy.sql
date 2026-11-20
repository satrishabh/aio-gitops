-- How each project's image is built (pipeline param build-strategy): auto | dockerfile | buildpacks | buildah.
-- buildah is accepted only while the "podman" platform switch is on (phase 6). Existing projects keep auto.
ALTER TABLE projects ADD COLUMN build_strategy VARCHAR(20) NOT NULL DEFAULT 'auto';
