#!/usr/bin/env python3
"""Assert that every LifeLink table has an explicit RLS decision.

LifeLink's primary authorization boundary is the FastAPI service: the Android
client never talks to PostgreSQL directly, and the API enforces ownership and
participation on every request. Supabase Row Level Security is therefore
*defense in depth* — it protects the data if the anon/publishable key is ever
used to reach PostgREST directly.

This check makes that decision explicit and enforceable:

* Every table created in ``lifelink_fastapi/sql/*.sql`` must either
  - have ``ALTER TABLE ... ENABLE ROW LEVEL SECURITY`` **and** at least one
    ``CREATE POLICY``, or
  - be listed in ``BACKEND_ONLY_TABLES`` below with a written justification.

A new table that is neither protected nor explicitly acknowledged fails the
check, so the gap can never grow silently.

Run locally:  python scripts/check_rls_policies.py
Exit code 0 = every table has a decision; 1 = at least one table is unaccounted for.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SQL_DIR = REPO_ROOT / "lifelink_fastapi" / "sql"

# Tables that are intentionally reachable only through the FastAPI service and
# are NOT exposed to PostgREST. They still rely on the API's ownership checks;
# RLS is not enabled because no client role is ever granted access to them.
# Keep this list small and justified — it is the documented gap.
BACKEND_ONLY_TABLES: dict[str, str] = {
    "facilities": "Reference data; read-only through the API, never queried by a client role.",
    "donors": "Donor rows are matched and filtered server-side; a client role must never read them directly.",
    "emergency_requests": "Requester-owned; the API scopes every read/write by authenticated subject.",
    "request_matches": "Derived matching state; only the API's matching engine writes it.",
    "pending_submissions": "Transient draft state owned by the API.",
    "lifelink_profiles": "Account profile; the API resolves it from verified JWT claims.",
    "donor_contact_requests": "Contact lifecycle; the API enforces participant checks.",
    "audit_events": "Append-only audit log; write-only from the API, never client-readable.",
    "donor_location_shares": "Exact-location shares; the API re-verifies the (request, donor) match on every read.",
    "conversations": "Chat threads; the API enforces participant membership.",
    "messages": "Chat messages; the API enforces participant membership.",
    "contact_shares": "Explicit contact disclosures; the API enforces participant membership.",
    "conversation_blocks": "Block state; the API enforces participant membership.",
}

# SQL comments must be removed before matching: a commented-out
# `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` or `CREATE POLICY` is not a real
# protection and must not satisfy the check.
_SQL_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
_SQL_LINE_COMMENT_RE = re.compile(r"--[^\n]*")


def _strip_sql_comments(sql: str) -> str:
    """Remove SQL block and line comments so only executable text is matched."""
    without_blocks = _SQL_BLOCK_COMMENT_RE.sub(" ", sql)
    return _SQL_LINE_COMMENT_RE.sub("", without_blocks)


CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-zA-Z_][a-zA-Z0-9_]*)",
    re.IGNORECASE,
)
ENABLE_RLS_RE = re.compile(
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\s+ENABLE\s+ROW\s+LEVEL\s+SECURITY",
    re.IGNORECASE,
)
CREATE_POLICY_RE = re.compile(
    r"CREATE\s+POLICY\s+[a-zA-Z0-9_]+\s+ON\s+([a-zA-Z_][a-zA-Z0-9_]*)",
    re.IGNORECASE,
)


def _read_sql() -> str:
    """Read every migration, with SQL comments stripped.

    Comments are removed so a commented-out RLS statement cannot be mistaken
    for an enforced one.
    """
    if not SQL_DIR.is_dir():
        raise SystemExit(f"SQL directory not found: {SQL_DIR}")
    raw = "\n".join(path.read_text(encoding="utf-8") for path in sorted(SQL_DIR.glob("*.sql")))
    return _strip_sql_comments(raw)


def main() -> int:
    """Check every table for an RLS policy or a documented exemption.

    Returns 0 when every table has an explicit decision, 1 otherwise.
    """
    sql = _read_sql()

    tables = {name.lower() for name in CREATE_TABLE_RE.findall(sql)}
    rls_enabled = {name.lower() for name in ENABLE_RLS_RE.findall(sql)}
    policies = {name.lower() for name in CREATE_POLICY_RE.findall(sql)}

    protected = {t for t in tables if t in rls_enabled and t in policies}
    acknowledged = {t for t in tables if t in BACKEND_ONLY_TABLES}
    unaccounted = sorted(tables - protected - acknowledged)

    print(f"Tables found: {len(tables)}")
    print(f"  RLS-enabled with a policy: {len(protected)}")
    print(f"  Documented backend-only:   {len(acknowledged)}")
    print(f"  Unaccounted for:           {len(unaccounted)}")

    if unaccounted:
        print("\nFAIL: the following tables have neither an RLS policy nor a documented exemption:")
        for table in unaccounted:
            print(f"  - {table}")
        print(
            "\nEither add `ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;` plus a "
            "`CREATE POLICY ... ON <t>` in a new migration, or add the table to "
            "BACKEND_ONLY_TABLES in scripts/check_rls_policies.py with a justification."
        )
        return 1

    print("\nOK: every table has an explicit RLS decision.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
