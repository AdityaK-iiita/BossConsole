import unittest

from app.bridge import (
    MAX_REQUEST_BYTES,
    extract_error_message,
    read_bounded_body,
    upstream_error_message,
)


class FakeRequest:
    """Only what _read_bounded_body uses: an async byte stream."""

    def __init__(self, chunks: list[bytes]) -> None:
        self.chunks = chunks
        self.consumed = 0

    async def stream(self):
        for chunk in self.chunks:
            self.consumed += len(chunk)
            yield chunk


class UpstreamErrorMessageTests(unittest.TestCase):
    def test_upstream_detail_never_reaches_the_client(self) -> None:
        leaky = b'{"error":{"message":"litellm.APIError: api_base=https://gw.cwinference.com/v1 key=sk-abc"}}'

        # The raw text is still parseable for the opt-in server-side log ...
        self.assertIn("cwinference", extract_error_message(leaky))
        # ... but what a client is told is written here.
        for status in (400, 401, 403, 404, 429, 500, 503, 599):
            with self.subTest(status=status):
                message = upstream_error_message(status)
                self.assertNotIn("cwinference", message)
                self.assertNotIn("sk-", message)
                self.assertTrue(message)

    def test_server_errors_share_a_retryable_message(self) -> None:
        self.assertEqual(upstream_error_message(500), upstream_error_message(503))

    def test_an_unmapped_client_status_still_gets_text(self) -> None:
        self.assertEqual(upstream_error_message(418), "The model gateway returned an error.")


class BoundedBodyTests(unittest.IsolatedAsyncioTestCase):
    async def test_reads_a_body_within_the_limit(self) -> None:
        request = FakeRequest([b'{"a":', b"1}"])

        self.assertEqual(await read_bounded_body(request, MAX_REQUEST_BYTES), b'{"a":1}')

    async def test_stops_reading_a_chunked_body_that_exceeds_the_limit(self) -> None:
        # A client that declares no Content-Length: the pre-check cannot fire, so
        # the read itself has to stop.
        request = FakeRequest([b"x" * 8, b"x" * 8, b"x" * 8])

        self.assertIsNone(await read_bounded_body(request, 10))
        self.assertLessEqual(request.consumed, 16, "must stop at the first chunk past the limit")


if __name__ == "__main__":
    unittest.main()
