#!/usr/bin/env python3
"""Inline the operational SQL for Supabase CLI's isolated test-file mounts.

Run before `supabase test db`. This only generates a test file; it never connects
or executes SQL. The generated file is ignored by Git and used on disposable DBs.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
template = root / "supabase/tests/master_key_rotation_test.sql.in"
operation = root / "supabase/ops/rotate_master_encryption_key.sql"
marker = r"\ir ../ops/rotate_master_encryption_key.sql"
source = template.read_text()
if source.count(marker) != 3:
    raise SystemExit("Expected three rotation invocations in the regression template")
template.with_suffix("").write_text(source.replace(marker, operation.read_text()))
