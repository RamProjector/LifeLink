from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_role_policy_findings_are_backend_only_in_migration_and_startup() -> None:
    migration = (ROOT / "sql" / "021_role_policy_integrity_findings.sql").read_text(
        encoding="utf-8"
    )
    startup = (ROOT / "app" / "db.py").read_text(encoding="utf-8")

    assert "ALTER TABLE lifelink_role_security_findings ENABLE ROW LEVEL SECURITY" in migration
    assert "DO $role_guard$" in migration
    assert "END;\n$role_guard$;" in migration
    assert "REVOKE ALL ON TABLE lifelink_role_security_findings FROM PUBLIC" in migration
    assert "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon')" in migration
    assert "REVOKE ALL ON TABLE lifelink_role_security_findings FROM anon" in migration
    assert "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated')" in migration
    assert "REVOKE ALL ON TABLE lifelink_role_security_findings FROM authenticated" in migration

    scanner_migration = (ROOT / "sql" / "022_security_scanner_health.sql").read_text(encoding="utf-8")
    assert "DO $role_guard$" in scanner_migration
    assert "END;\n$role_guard$;" in scanner_migration
    assert "REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM PUBLIC" in scanner_migration
    assert "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon')" in scanner_migration
    assert "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated')" in scanner_migration

    assert (
        '"REVOKE ALL ON TABLE lifelink_role_security_findings FROM PUBLIC"'
    ) in startup
    assert (
        '"REVOKE ALL ON TABLE lifelink_role_security_findings FROM anon"'
    ) in startup
    assert (
        '"REVOKE ALL ON TABLE lifelink_role_security_findings FROM authenticated"'
    ) in startup
