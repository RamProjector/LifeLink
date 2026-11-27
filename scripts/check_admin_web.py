#!/usr/bin/env python3
"""Static integrity checks for the LifeLink Admin Console."""
from __future__ import annotations

import base64
import hashlib
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
MFA_JS_PATH = ROOT / "admin-web" / "mfa.js"
MFA_TEST_PATH = ROOT / "scripts" / "test_admin_mfa.js"
ADMIN_API_PATH = ROOT / "lifelink_fastapi" / "app" / "admin_api.py"


class DocumentCheck(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: list[str] = []
        self.icon_names: list[str] = []
        self.icon_script_found = False
        self.mfa_script_found = False
        self.inline_handlers: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.append(values["id"] or "")
        if values.get("data-icon"):
            self.icon_names.append(values["data-icon"] or "")
        self.inline_handlers.extend(name for name, _ in attrs if name.lower().startswith("on"))
        if tag == "script" and values.get("src") == "./icons.js":
            self.icon_script_found = True
        if tag == "script" and values.get("src") == "./mfa.js":
            self.mfa_script_found = True


def fail(message: str) -> None:
    print(f"Admin website check failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if not HTML_PATH.is_file() or not ICONS_PATH.is_file() or not MFA_JS_PATH.is_file() or not MFA_TEST_PATH.is_file() or not ADMIN_API_PATH.is_file():
        fail("admin website entry point, SVG icon library, or MFA module/tests are missing")

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

    csp_match = re.search(
        r'<meta\s+http-equiv="Content-Security-Policy"\s+content="([^"]+)"',
        html,
        re.IGNORECASE,
    )
    if not csp_match:
        fail("admin console must declare a Content Security Policy")
    csp = csp_match.group(1)
    script_hash = base64.b64encode(hashlib.sha256(inline_scripts[0].encode("utf-8")).digest()).decode("ascii")
    if f"'sha256-{script_hash}'" not in csp:
        fail("Content Security Policy hash does not match the inline application script")
    script_policy = re.search(r"(?:^|;)\s*script-src\s+([^;]+)", csp)
    if not script_policy or "'unsafe-inline'" in script_policy.group(1):
        fail("inline application scripts must be hash-authorized, not allowed with unsafe-inline")
    if "object-src 'none'" not in csp:
        fail("Content Security Policy must disable embedded object content")
    if parser.inline_handlers:
        fail("admin HTML must not use inline event-handler attributes")
    if "localStorage" in html or "sessionStorage" in html:
        fail("administrator access and recovery tokens must not be persisted in browser storage")
    if 'name="referrer" content="no-referrer"' not in html or 'name="robots" content="noindex,nofollow"' not in html:
        fail("admin console must prevent referrer leakage and search indexing")

    if (
        "body:has(#login-view:not(.hidden))" not in html
        or " > .top { display: none; }" not in html
        or "width: min(100%, 480px)" not in html
    ):
        fail("login and password recovery must use a centered authentication surface without workspace navigation")

    if (
        'id="page-title" tabindex="-1"' not in html
        or 'if(previousPage!==page){$("page-title").focus({preventScroll:true});window.scrollTo(0,0);}' not in html
    ):
        fail("admin section navigation must restore the page start and focus its heading")

    if (
        "function table(items, cols, options={})" not in html
        or "options.disableLocalStatusFilter?null:" not in html
        or "],{disableLocalStatusFilter:true});" not in html
    ):
        fail("security alerts must not duplicate their server-side status filter with a page-local filter")

    duplicates = [key for key, count in Counter(parser.ids).items() if count > 1]
    if duplicates:
        fail("duplicate HTML id attributes: " + ", ".join(sorted(duplicates)))
    if not parser.icon_script_found:
        fail("index.html does not load the reusable ./icons.js module")
    if not parser.mfa_script_found:
        fail("index.html does not load the admin MFA transport module")
    for javascript_path in (MFA_JS_PATH, MFA_TEST_PATH):
        result = subprocess.run(
            ["node", "--check", str(javascript_path)],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode:
            fail(f"JavaScript syntax error in {javascript_path.name}: " + result.stderr.strip())

    mfa_tests = subprocess.run(
        ["node", str(MFA_TEST_PATH)],
        capture_output=True,
        text=True,
        check=False,
    )
    if mfa_tests.returncode:
        fail("admin MFA transport regression tests failed: " + mfa_tests.stderr.strip())
    mfa_source = MFA_JS_PATH.read_text(encoding="utf-8")
    if "localStorage" in mfa_source or "sessionStorage" in mfa_source:
        fail("admin MFA tokens must remain in memory and never use browser storage")

    if "createElementNS(NS" not in icons or "currentColor" not in icons:
        fail("SVG icons must be created with the SVG DOM API and inherit currentColor")
    if "innerHTML" in icons or "eval(" in icons:
        fail("SVG icon library must not inject HTML or evaluate code")

    declared = set(re.findall(r"^    ([A-Za-z][A-Za-z0-9]*): \[\[", icons, re.MULTILINE))
    missing = sorted(set(parser.icon_names) - declared)
    if missing:
        fail("HTML references undefined SVG icons: " + ", ".join(missing))

    if (
        "overview-chart" not in html
        or "function renderOverviewBars(data)" not in html
        or "const snapshot={...d,users,requests}" not in html
        or "renderOverviewBars(snapshot)" not in html
    ):
        fail("overview must visualize validated live dashboard totals with an accessible chart")

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

    if '"s.expires_at, s.revoked_at, s.revoked_by, s.status,' not in admin_api or 's.revoked_by||"Unavailable"' not in html:
        fail("session management must show the recorded revocation timestamp and actor")

    if (
        "COALESCE(matched.correlation_id, latest.correlation_id) AS last_correlation_id" not in admin_api
        or "CAST(:correlation_id AS varchar) IS NULL" not in admin_api
        or 'id="sessions-correlation-filter"' not in html
        or 'id="alerts-correlation-filter"' not in html
        or "a.correlation_id" not in html
    ):
        fail("sessions and security alerts must expose and filter related correlation identifiers")

    required_ids = {"login-view", "mfa-view", "mfa-step-enroll", "mfa-step-challenge", "mfa-step-manage", "app-view", "page-overview", "page-users", "page-alerts", "page-sessions", "page-audit", "page-errors", "page-analytics", "page-roles"}
    missing_ids = sorted(required_ids - set(parser.ids))
    if missing_ids:
        fail("required admin page containers are missing: " + ", ".join(missing_ids))

    if (
        'id="mfa-view"' not in html
        or "function enterAdminConsole()" not in html
        or 'assuranceLevel(accessToken)!=="aal2"' not in html
        or "verified.length<2" not in html
        or "function verifyMfaChallenge()" not in html
        or "function verifyMfaEnrollment()" not in html
        or "async function removeMfaFactor" not in html
        or "initial.length<=2" not in html
        or "unenroll(currentMfaConfig(),factorId)" not in html
        or 'backup=mfaMode==="backup"||mfaMode==="manage-add"' not in html
        or "mfaReturnToManage" not in html
        or 'id="manage-mfa"' not in html
    ):
        fail("admin console must require AAL2 and two verified TOTP factors, with replacement-before-removal controls")

    sign_in_start = html.find("async function signIn()")
    sign_in_end = html.find("function table(items, cols)", sign_in_start)
    if sign_in_start < 0 or sign_in_end < 0:
        fail("admin sign-in flow is missing")
    sign_in_flow = html[sign_in_start:sign_in_end]
    admin_check = sign_in_flow.find('await api("/v1/admin/preflight")')
    factor_discovery = sign_in_flow.find("mfaFactors=await")
    if admin_check < 0 or factor_discovery < 0 or admin_check > factor_discovery:
        fail("identity-only server-side admin preflight must run before MFA enrollment")
    enter_start = html.find("async function enterAdminConsole()")
    enter_end = html.find("function showBackupEnrollment()", enter_start)
    if enter_start < 0 or enter_end < 0 or 'await api("/v1/admin/me")' not in html[enter_start:enter_end]:
        fail("full admin identity response must be requested only after the AAL2 enrollment/challenge gate")
    if "mfaFlowGeneration" not in html or "if(flow!==mfaFlowGeneration)" not in html:
        fail("MFA enrollment and verification must ignore stale asynchronous responses after cancellation")
    management_start = html.find("async function showMfaManagement()")
    management_end = html.find("function showAddMfaFactor()", management_start)
    if management_start < 0 or management_end < 0:
        fail("MFA factor management flow is missing")
    management_flow = html[management_start:management_end]
    if "const flow=mfaFlowGeneration" not in management_flow or management_flow.count("if(flow!==mfaFlowGeneration)") < 2:
        fail("MFA factor management must ignore stale responses after cancellation")
    sign_in_flow = html[sign_in_start:sign_in_end]
    if "const flow=++mfaFlowGeneration" not in sign_in_flow or "if(flow!==mfaFlowGeneration)return" not in sign_in_flow:
        fail("Admin sign-in must not restore a stale session after cancellation")

    print(f"Admin website integrity passed: {len(parser.ids)} unique IDs, {len(set(parser.icon_names))} SVG icon types.")


if __name__ == "__main__":
    main()

