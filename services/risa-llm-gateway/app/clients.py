"""HTTP clients for Supabase identity and LiteLLM key management."""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from typing import Any

import httpx

from .broker import BrokerError, IssuedKey, VerifiedUser, unique_key_alias
from .cloudrun_auth import IdentityTokenError, IdentityTokenProvider
from .settings import Settings


class SupabaseIdentityClient:
    def __init__(self, http: httpx.AsyncClient, settings: Settings) -> None:
        self.http = http
        self.settings = settings

    async def verify_access_token(self, token: str) -> VerifiedUser:
        response = await self.http.get(
            f"{self.settings.supabase_url}/auth/v1/user",
            headers={
                "apikey": self.settings.supabase_anon_key,
                "Authorization": f"Bearer {token}",
            },
        )
        if response.status_code != 200:
            raise BrokerError(401, "BOSS session is invalid or expired")

        body = _json_object(response, "Supabase returned an invalid user response")
        user_id = body.get("id")
        email = body.get("email")
        if not isinstance(user_id, str) or not isinstance(email, str):
            raise BrokerError(502, "Supabase returned an incomplete user response")
        confirmed = bool(body.get("email_confirmed_at") or body.get("confirmed_at"))
        return VerifiedUser(user_id=user_id, email=email, email_confirmed=confirmed)


@dataclass
class _CachedKey:
    key: str
    expires_at: str
    reuse_until: float


class LiteLlmKeyIssuer:
    """
    Issues model-scoped LiteLLM keys, one live key per user at a time.

    Two things bound what one account can spend, because per-key limits alone
    bound nothing: a caller that asks twice simply holds two keys and doubles its
    own ceiling.

    1. A live key is reused until its refresh window elapses, so a client that
       calls /auth/token in a loop gets the same credential back.
    2. The limits are also set on the LiteLLM *user*, which LiteLLM applies across
       every key that user holds. That is the durable ceiling; the cache is only a
       per-instance optimisation and does not survive a Cloud Run scale-out.
    """

    def __init__(
        self,
        http: httpx.AsyncClient,
        settings: Settings,
        identity_tokens: IdentityTokenProvider,
    ) -> None:
        self.http = http
        self.settings = settings
        self.identity_tokens = identity_tokens
        self._cache: dict[str, _CachedKey] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def issue_key(self, user: VerifiedUser) -> IssuedKey:
        # Per-user lock: without it, concurrent Codex processes on one laptop
        # each miss the cache and mint their own key, which is the case the cache
        # exists to prevent.
        lock = self._locks.setdefault(user.user_id, asyncio.Lock())
        async with lock:
            cached = self._cache.get(user.user_id)
            now = time.monotonic()
            if cached is not None and cached.reuse_until > now:
                return IssuedKey(
                    key=cached.key,
                    expires_at=cached.expires_at,
                    refresh_after_seconds=int(cached.reuse_until - now),
                )
            return await self._mint(user)

    async def _mint(self, user: VerifiedUser) -> IssuedKey:
        headers = await self._litellm_headers()
        await self._apply_user_limits(user, headers)

        response = await self.http.post(
            f"{self.settings.litellm_url}/key/generate",
            headers=headers,
            json={
                "models": [self.settings.model],
                "user_id": user.user_id,
                "duration": self.settings.key_duration,
                "rpm_limit": self.settings.rpm_limit,
                "tpm_limit": self.settings.tpm_limit,
                # LiteLLM requires aliases to be globally unique. A client can
                # lose the first response during a cold start and retry, so a
                # deterministic per-user alias makes otherwise safe retries
                # fail. Attribution remains stable through user_id/metadata.
                "key_alias": unique_key_alias(),
                "metadata": {
                    "client": "boss-codex",
                    "supabase_user_id": user.user_id,
                },
            },
        )
        if response.status_code != 200:
            raise BrokerError(502, "Could not issue a RISA LLM access token")

        body = _json_object(response, "LiteLLM returned an invalid key response")
        key = body.get("key")
        expires = body.get("expires")
        if not isinstance(key, str) or not key or not isinstance(expires, str) or not expires:
            raise BrokerError(502, "LiteLLM returned an incomplete key response")

        reuse_seconds = self.settings.key_refresh_seconds
        self._cache[user.user_id] = _CachedKey(
            key=key,
            expires_at=expires,
            reuse_until=time.monotonic() + reuse_seconds,
        )
        return IssuedKey(key=key, expires_at=expires, refresh_after_seconds=reuse_seconds)

    async def _apply_user_limits(self, user: VerifiedUser, headers: dict[str, str]) -> None:
        """
        Persist the ceiling on the LiteLLM user, so it holds across every key.

        /user/new rejects a user that already exists, so an existing one is
        updated instead. Failing to apply the ceiling fails the request: this is
        the spend control, and issuing an unbounded key because the limits could
        not be written is the outcome it exists to prevent.
        """
        payload: dict[str, Any] = {
            "user_id": user.user_id,
            "rpm_limit": self.settings.rpm_limit,
            "tpm_limit": self.settings.tpm_limit,
            "max_parallel_requests": self.settings.max_parallel_requests,
        }
        if self.settings.user_max_budget is not None:
            payload["max_budget"] = self.settings.user_max_budget
            payload["budget_duration"] = self.settings.user_budget_duration

        for endpoint in ("/user/new", "/user/update"):
            try:
                response = await self.http.post(
                    f"{self.settings.litellm_url}{endpoint}",
                    headers=headers,
                    json=payload,
                )
            except httpx.HTTPError as exc:
                raise BrokerError(502, "Could not reach the model gateway") from exc
            if response.status_code == 200:
                return

        raise BrokerError(502, "Could not apply the RISA LLM usage ceiling")

    async def _litellm_headers(self) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self.settings.litellm_master_key}",
            "Content-Type": "application/json",
        }
        try:
            identity_token = await self.identity_tokens.token()
        except IdentityTokenError as exc:
            raise BrokerError(502, "Could not authenticate to the model gateway") from exc
        if identity_token:
            headers["X-Serverless-Authorization"] = f"Bearer {identity_token}"
        return headers


def _json_object(response: httpx.Response, error_message: str) -> dict[str, Any]:
    try:
        body = response.json()
    except ValueError as exc:
        raise BrokerError(502, error_message) from exc
    if not isinstance(body, dict):
        raise BrokerError(502, error_message)
    return body
