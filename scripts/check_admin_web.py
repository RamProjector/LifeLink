#!/usr/bin/env python3
"""Static integrity checks for the LifeLink Admin Console."""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTML_PATH = ROOT / "admin-web" / "index.html"
ICONS_PATH = ROOT / "admin-web" / "icons.js"
ADMIN_API_PATH = ROOT / "lifelink_fastapi" / "app" / "admin_api.py"


class DocumentCheck(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: list[str] = []
        self.icon_names: list[str] = []
        self.icon_script_found = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.append(values["id"] or "")
        if values.get("data-icon"):
            self.icon_names.append(values["data-icon"] or "")
        if tag == "script" and values.get("src") == "./icons.js":
            self.icon_script_found = True


def fail(message: str) -> None:
    print(f"Admin website check failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if not HTML_PATH.is_file() or not ICONS_PATH.is_file() or not ADMIN_API_PATH.is_file():
        fail("admin website entry point or SVG icon library is missing")

    html = HTML_PATH.read_text(encoding="utf-8")
    icons = ICONS_PATH.read_text(encoding="utf-8")
    admin_api = ADMIN_API_PATH.read_text(encoding="utf-8")
    parser = DocumentCheck()
    parser.feed(html)
    parser.close()

    if not shutil.which("node"):
        fail("Node.js is required to syntax-check the inline admin script")
    inline_scripts = re.findall(r"<script>(.*?)</script>", html, re.DOTALL | re.IGNORECASE)
    if len(inline_scripts) != 1:
        fail(f"expected exactly one inline application script, found {len(inline_scripts)}")
    with tempfile.NamedTemporaryFile(mode="w", suffix=".js", encoding="utf-8") as script_file:
        script_file.write(inline_scripts[0])
        script_file.flush()
        result = subprocess.run(["node", "--check", script_file.name], capture_output=True, text=True, check=False)
        if result.returncode:
            fail("inline application JavaScript syntax error: " + result.stderr.strip())

    duplicates = [key for key, count in Counter(parser.ids).items() if count > 1]
    if duplicates:
        fail("duplicate HTML id attributes: " + ", ".join(sorted(duplicates)))
    if not parser.icon_script_found:
        fail("index.html does not load the reusable ./icons.js module")
    if "createElementNS(NS" not in icons or "currentColor" not in icons:
        fail("SVG icons must be created with the SVG DOM API and inherit currentColor")
    if "innerHTML" in icons or "eval(" in icons:
        fail("SVG icon library must not inject HTML or evaluate code")

    declared = set(re.findall(r"^    ([A-Za-z][A-Za-z0-9]*): \[\[", icons, re.MULTILINE))
    missing = sorted(set(parser.icon_names) - declared)
    if missing:
        fail("HTML references undefined SVG icons: " + ", ".join(missing))

    if "overview-chart" not in html or "function renderOverviewBars(data)" not in html or "renderOverviewBars(d)" not in html:
        fail("overview must visualize live dashboard record totals with an accessible chart")

    if "data-table-export" not in html or "URL.createObjectURL(blob)" not in html:
        fail("admin data tables must provide client-side CSV export")
    if "const csvCell" not in html or "rows.filter(row=>!row.hidden)" not in html:
        fail("CSV export must serialize filtered rows through a dedicated cell encoder")
    if "if(/^[=+@" not in html or "text=" not in html:
        fail("CSV export must neutralize spreadsheet formula starters")

    if "audit-action-filter" not in html or "auditAction?" not in html or "audit-filter-apply" not in html:
        fail("audit history must expose its exact-action server-side filter")

    if "audit-correlation-filter" not in html or "errors-correlation-filter" not in html or "correlation_id" not in admin_api:
        fail("audit and error history must support server-side correlation ID tracing")
    if '"SELECT id, log_id, actor_id, action, actions, description, session_id, ip_address, timestamp, device_used, "' not in admin_api:
        fail("error history must return the operational context used by the event table")

    if 'id="alert-status-filter"' not in html or 'alertStatus?"&status="' not in html:
        fail("security alerts must expose and use the server-side status filter")

    if 'data-status="false_positive"' not in html or "a.acknowledged_at" not in html or "a.resolved_at" not in html:
        fail("security alerts must support false-positive disposition and show their recorded handling history")

    if '"expires_at, revoked_at, revoked_by, status "' not in admin_api or 's.revoked_by||"Unavailable"' not in html:
        fail("session management must show the recorded revocation timestamp and actor")

    required_ids = {"login-view", "app-view", "page-overview", "page-users", "page-alerts", "page-sessions", "page-audit", "page-errors", "page-analytics", "page-roles"}
    missing_ids = sorted(required_ids - set(parser.ids))
    if missing_ids:
        fail("required admin page containers are missing: " + ", ".join(missing_ids))

    print(f"Admin website integrity passed: {len(parser.ids)} unique IDs, {len(set(parser.icon_names))} SVG icon types.")


if __name__ == "__main__":
    main()

