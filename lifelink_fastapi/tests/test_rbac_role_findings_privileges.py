from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_role_policy_findings_are_backend_only_in_migration_and_startup() -> None:
    migration = (ROOT / "sql" / "021_role_policy_integrity_findings.sql").read_text(
        encoding="utf-8"
    )
    startup = (ROOT / "app" / "db.py").read_text(encoding="utf-8")

    assert "ALTER TABLE lifelink_role_security_findings ENABLE ROW LEVEL SECURITY" in migration
    assert (
        "REVOKE ALL ON TABLE lifelink_role_security_findings "
        "FROM PUBLIC, anon, authenticated"
    ) in migration

    assert (
        '"REVOKE ALL ON TABLE lifelink_role_security_findings FROM PUBLIC"'
    ) in startup
    assert (
        '"REVOKE ALL ON TABLE lifelink_role_security_findings FROM anon"'
    ) in startup
    assert (
        '"REVOKE ALL ON TABLE lifelink_role_security_findings FROM authenticated"'
    ) in startup
