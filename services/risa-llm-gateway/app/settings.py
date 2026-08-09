"""Environment-backed gateway configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} is required")
    return value


def optional_float_env(name: str) -> float | None:
    """A spend cap the operator has not set yet, rather than a cap of zero."""
    raw = os.environ.get(name, "").strip()
    return float(raw) if raw else None


def email_allowlist_env(name: str) -> frozenset[str]:
    raw = required_env(name)
    emails = frozenset(value.strip().lower() for value in raw.split(",") if value.strip())
    if not emails:
        raise RuntimeError(f"{name} must contain at least one email address")
    return emails


@dataclass(frozen=True)
class Settings:
    supabase_url: str
    supabase_anon_key: str
    gateway_database_url: str
    litellm_url: str
    litellm_id_token_audience: str
    litellm_master_key: str
    allowed_email_domain: str
    allowed_emails: frozenset[str]
    model: str
    key_duration: str
    key_refresh_seconds: int
    rpm_limit: int
    tpm_limit: int
    max_parallel_requests: int
    user_max_budget: float | None
    user_budget_duration: str
    log_upstream_errors: bool

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            supabase_url=required_env("SUPABASE_URL").rstrip("/"),
            supabase_anon_key=required_env("SUPABASE_ANON_KEY"),
            gateway_database_url=required_env("GATEWAY_DATABASE_URL"),
            litellm_url=os.environ.get("LITELLM_URL", "http://litellm:4000").rstrip("/"),
            litellm_id_token_audience=os.environ.get("LITELLM_ID_TOKEN_AUDIENCE", "").strip(),
            litellm_master_key=required_env("LITELLM_MASTER_KEY"),
            allowed_email_domain=os.environ.get("RISA_LLM_EMAIL_DOMAIN", "risalabs.ai").lower(),
            allowed_emails=email_allowlist_env("RISA_LLM_ALLOWED_EMAILS"),
            model=os.environ.get("RISA_LLM_MODEL", "coreweave-glm-5-2"),
            key_duration=os.environ.get("RISA_LLM_KEY_DURATION", "8h"),
            key_refresh_seconds=int(os.environ.get("RISA_LLM_KEY_REFRESH_SECONDS", "21600")),
            rpm_limit=int(os.environ.get("RISA_LLM_RPM_LIMIT", "60")),
            tpm_limit=int(os.environ.get("RISA_LLM_TPM_LIMIT", "500000")),
            max_parallel_requests=int(os.environ.get("RISA_LLM_MAX_PARALLEL_REQUESTS", "8")),
            user_max_budget=optional_float_env("RISA_LLM_USER_MAX_BUDGET"),
            user_budget_duration=os.environ.get("RISA_LLM_USER_BUDGET_DURATION", "30d"),
            # Off by default: an upstream error body can quote the request that
            # produced it, and prompt logging is prohibited at this gateway.
            log_upstream_errors=os.environ.get("RISA_LLM_LOG_UPSTREAM_ERRORS", "").strip().lower()
            in ("1", "true", "yes"),
        )
