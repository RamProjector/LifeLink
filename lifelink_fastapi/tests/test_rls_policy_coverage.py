"""Guardrail: every LifeLink table must have an explicit RLS decision.

This wraps ``scripts/check_rls_policies.py`` so the rule is enforced by the
normal backend test run, not only by a separate CI step. See that script for
the rationale and the documented backend-only exemptions.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "check_rls_policies.py"


def _load_checker():
    """Import ``scripts/check_rls_policies.py`` as a module for the test."""
    spec = importlib.util.spec_from_file_location("check_rls_policies", SCRIPT)
    assert spec and spec.loader, f"cannot load {SCRIPT}"
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_every_table_has_an_rls_decision() -> None:
    """Every table must have an RLS policy or a documented backend-only exemption."""
    checker = _load_checker()
    assert checker.main() == 0, (
        "A table has neither an RLS policy nor a documented backend-only "
        "exemption. See scripts/check_rls_policies.py."
    )
