import asyncio
import unittest

from app.broker import BrokerError, VerifiedUser
from app.clients import LiteLlmKeyIssuer
from app.settings import Settings


def settings(**overrides) -> Settings:
    base = dict(
        supabase_url="https://api.example.com",
        supabase_anon_key="anon",
        gateway_database_url="postgres://localhost/gateway",
        litellm_url="http://litellm:4000",
        litellm_id_token_audience="",
        litellm_master_key="sk-master",
        allowed_email_domain="risalabs.ai",
        allowed_emails=frozenset({"person@risalabs.ai"}),
        model="coreweave-glm-5-2",
        key_duration="8h",
        key_refresh_seconds=21600,
        rpm_limit=60,
        tpm_limit=500000,
        max_parallel_requests=8,
        user_max_budget=None,
        user_budget_duration="30d",
        log_upstream_errors=False,
    )
    base.update(overrides)
    return Settings(**base)


class FakeResponse:
    def __init__(self, status_code: int, body: dict) -> None:
        self.status_code = status_code
        self._body = body

    def json(self) -> dict:
        return self._body


class FakeHttp:
    """Records every POST and answers from a per-path script."""

    def __init__(self, user_new_status: int = 200) -> None:
        self.posts: list[tuple[str, dict]] = []
        self.user_new_status = user_new_status
        self._generated = 0

    async def post(self, url: str, headers: dict, json: dict) -> FakeResponse:
        self.posts.append((url, json))
        if url.endswith("/user/new"):
            return FakeResponse(self.user_new_status, {})
        if url.endswith("/user/update"):
            return FakeResponse(200, {})
        self._generated += 1
        return FakeResponse(
            200,
            {"key": f"sk-generated-{self._generated}", "expires": "2026-08-06T12:00:00Z"},
        )

    def paths(self) -> list[str]:
        return [url.rsplit("/", 2)[-2] + "/" + url.rsplit("/", 1)[-1] for url, _ in self.posts]


class NoIdentity:
    async def token(self) -> str | None:
        return None


def person() -> VerifiedUser:
    return VerifiedUser(user_id="user-123", email="person@risalabs.ai", email_confirmed=True)


class KeyIssuerTests(unittest.IsolatedAsyncioTestCase):
    async def test_reuses_a_live_key_instead_of_minting_per_call(self) -> None:
        http = FakeHttp()
        issuer = LiteLlmKeyIssuer(http, settings(), NoIdentity())

        first = await issuer.issue_key(person())
        second = await issuer.issue_key(person())

        self.assertEqual(first.key, second.key)
        generated = [url for url, _ in http.posts if url.endswith("/key/generate")]
        self.assertEqual(len(generated), 1, "a second call must not mint a second key")

    async def test_reused_key_reports_its_remaining_window(self) -> None:
        http = FakeHttp()
        issuer = LiteLlmKeyIssuer(http, settings(key_refresh_seconds=600), NoIdentity())

        first = await issuer.issue_key(person())
        second = await issuer.issue_key(person())

        self.assertEqual(first.refresh_after_seconds, 600)
        # The reused key has less life left, so the client must be told less.
        self.assertLessEqual(second.refresh_after_seconds, 600)

    async def test_concurrent_callers_share_one_key(self) -> None:
        http = FakeHttp()
        issuer = LiteLlmKeyIssuer(http, settings(), NoIdentity())

        keys = await asyncio.gather(*(issuer.issue_key(person()) for _ in range(5)))

        self.assertEqual({key.key for key in keys}, {"sk-generated-1"})
        generated = [url for url, _ in http.posts if url.endswith("/key/generate")]
        self.assertEqual(len(generated), 1)

    async def test_applies_the_ceiling_to_the_litellm_user(self) -> None:
        http = FakeHttp()
        issuer = LiteLlmKeyIssuer(http, settings(), NoIdentity())

        await issuer.issue_key(person())

        user_calls = [body for url, body in http.posts if url.endswith("/user/new")]
        self.assertEqual(len(user_calls), 1)
        self.assertEqual(user_calls[0]["user_id"], "user-123")
        self.assertEqual(user_calls[0]["rpm_limit"], 60)
        self.assertEqual(user_calls[0]["max_parallel_requests"], 8)

    async def test_existing_user_is_updated_rather_than_recreated(self) -> None:
        http = FakeHttp(user_new_status=400)
        issuer = LiteLlmKeyIssuer(http, settings(), NoIdentity())

        await issuer.issue_key(person())

        self.assertTrue(any(url.endswith("/user/update") for url, _ in http.posts))
        self.assertTrue(any(url.endswith("/key/generate") for url, _ in http.posts))

    async def test_no_key_is_issued_when_the_ceiling_cannot_be_applied(self) -> None:
        class RefusingHttp(FakeHttp):
            async def post(self, url: str, headers: dict, json: dict) -> FakeResponse:
                self.posts.append((url, json))
                if "/user/" in url:
                    return FakeResponse(500, {})
                return FakeResponse(200, {"key": "sk-unbounded", "expires": "2026-08-06T12:00:00Z"})

        http = RefusingHttp()
        issuer = LiteLlmKeyIssuer(http, settings(), NoIdentity())

        with self.assertRaises(BrokerError):
            await issuer.issue_key(person())

        self.assertFalse(
            any(url.endswith("/key/generate") for url, _ in http.posts),
            "an unbounded key must never be issued because the ceiling failed",
        )

    async def test_budget_is_sent_only_when_configured(self) -> None:
        without = FakeHttp()
        await LiteLlmKeyIssuer(without, settings(), NoIdentity()).issue_key(person())
        body = next(body for url, body in without.posts if url.endswith("/user/new"))
        self.assertNotIn("max_budget", body)

        with_budget = FakeHttp()
        await LiteLlmKeyIssuer(with_budget, settings(user_max_budget=25.0), NoIdentity()).issue_key(person())
        body = next(body for url, body in with_budget.posts if url.endswith("/user/new"))
        self.assertEqual(body["max_budget"], 25.0)
        self.assertEqual(body["budget_duration"], "30d")


if __name__ == "__main__":
    unittest.main()
