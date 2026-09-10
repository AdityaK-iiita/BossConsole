#!/usr/bin/env python3
"""Inline operational SQL for Supabase CLI's isolated test-file mounts.

Run before `supabase test db`. This only generates test files; it never connects
or executes SQL. Generated files are ignored by Git and used on disposable DBs.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
for name, operation, count in [
    ("master_key_rotation", "ops/rotate_master_encryption_key.sql", 5),
    ("db_access_audit", "audit/identity_disclosure_audit.sql", 3),
]:
    template = root / f"supabase/tests/{name}_test.sql.in"
    marker = rf"\ir ../{operation}"
    source = template.read_text()
    if source.count(marker) != count:
        raise SystemExit(f"Expected {count} source invocations in {template.name}")
    generated = source.replace(marker, (root / "supabase" / operation).read_text())
    if re.search(r"(?m)^\s*\\(?:ir|i)\b", generated):
        raise SystemExit(f"Unexpanded include in {template.name}")
    template.with_suffix("").write_text(generated)
