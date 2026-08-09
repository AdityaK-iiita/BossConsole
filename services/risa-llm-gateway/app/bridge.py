"""Translate Codex namespace tools for OpenAI-compatible vLLM endpoints."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any


MAX_FLATTENED_TOOLS = 64
MAX_TOOL_CATALOG_BYTES = 128 * 1024
MAX_ERROR_CHARS = 1600
MAX_REQUEST_BYTES = 4 * 1024 * 1024

# Client-facing text for an upstream failure, written here rather than forwarded.
# Upstream error bodies from LiteLLM/vLLM routinely carry the model config and
# api_base, so the client gets a message this gateway owns. Keyed by status, with
# a status-class fallback so a new upstream status degrades to a generic entry.
UPSTREAM_ERROR_MESSAGES = {
    400: "The model gateway rejected the request.",
    401: "The RISA LLM credential is not valid or has expired. Restart Codex to fetch a new one.",
    403: "The RISA LLM credential is not permitted to use this model.",
    404: "The requested model is not available on the RISA LLM gateway.",
    413: "Request body is too large",
    429: "The RISA LLM rate limit was reached. Wait a moment and retry.",
}


def upstream_error_message(status_code: int) -> str:
    if status_code in UPSTREAM_ERROR_MESSAGES:
        return UPSTREAM_ERROR_MESSAGES[status_code]
    if status_code >= 500:
        return "The model gateway is temporarily unavailable. Retry shortly."
    return "The model gateway returned an error."


async def read_bounded_body(request: Any, limit: int) -> bytes | None:
    """
    Read at most [limit] bytes from [request], returning None once it exceeds them.

    Streams rather than calling `request.body()`: that buffers the whole body
    before any check can run, so a chunked request with no Content-Length is
    unbounded however the length header is validated.

    Takes [request] as Any so this module stays importable without FastAPI, which
    is what lets the tests cover it - nothing here needs more than `.stream()`.
    """
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > limit:
            return None
        chunks.append(chunk)
    return b"".join(chunks)


class BridgeRequestError(ValueError):
    """The request cannot be represented safely for the upstream model."""


@dataclass(frozen=True)
class ToolRoute:
    namespace: str
    name: str
    qualified_name: str


def qualified_tool_name(namespace: str, tool_name: str) -> str:
    separator = "" if namespace.endswith("__") else "__"
    return f"{namespace}{separator}{tool_name}"


def tool_alias(qualified_name: str) -> str:
    digest = hashlib.sha256(qualified_name.encode("utf-8")).hexdigest()[:16]
    return f"cwtool_{digest}"


def flatten_namespace_tools(
    payload: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, ToolRoute]]:
    tools = payload.get("tools")
    if not isinstance(tools, list):
        return payload, {}

    flattened: list[dict[str, Any]] = []
    aliases: dict[str, ToolRoute] = {}
    for tool in tools:
        if not isinstance(tool, dict) or tool.get("type") != "namespace":
            flattened.append(tool)
            continue

        namespace = tool.get("name")
        children = tool.get("tools")
        if not isinstance(namespace, str) or not namespace or not isinstance(children, list):
            raise BridgeRequestError("Codex sent a malformed namespace tool bundle")

        for child in children:
            if not isinstance(child, dict) or child.get("type") != "function":
                raise BridgeRequestError(f"namespace {namespace!r} contains a non-function tool")
            child_name = child.get("name")
            if not isinstance(child_name, str) or not child_name:
                raise BridgeRequestError(f"namespace {namespace!r} contains an unnamed tool")

            qualified = qualified_tool_name(namespace, child_name)
            alias = tool_alias(qualified)
            route = ToolRoute(namespace, child_name, qualified)
            if alias in aliases and aliases[alias] != route:
                raise BridgeRequestError("namespace tool alias collision")
            aliases[alias] = route

            function_tool = dict(child)
            function_tool["name"] = alias
            namespace_description = tool.get("description")
            child_description = child.get("description") or ""
            prefix = f"Codex tool {qualified}."
            if isinstance(namespace_description, str) and namespace_description:
                prefix += f" Namespace: {namespace_description[:400]}"
            function_tool["description"] = f"{prefix}\n{child_description}".strip()
            flattened.append(function_tool)

    encoded_size = len(json.dumps(flattened, separators=(",", ":")).encode("utf-8"))
    if len(flattened) > MAX_FLATTENED_TOOLS or encoded_size > MAX_TOOL_CATALOG_BYTES:
        raise BridgeRequestError(
            "Codex tool catalog is too large for the RISA CoreWeave gateway "
            f"({len(flattened)} tools, {encoded_size} bytes). Enable only the tools "
            "needed for this task."
        )

    rewritten = dict(payload)
    rewritten["tools"] = flattened
    rewritten = rewrite_request_tool_calls(rewritten, aliases)
    return rewritten, aliases


def rewrite_request_tool_calls(value: Any, aliases: dict[str, ToolRoute]) -> Any:
    if isinstance(value, list):
        return [rewrite_request_tool_calls(item, aliases) for item in value]
    if not isinstance(value, dict):
        return value

    rewritten = {key: rewrite_request_tool_calls(item, aliases) for key, item in value.items()}
    if rewritten.get("type") != "function_call":
        return rewritten

    namespace = rewritten.get("namespace")
    name = rewritten.get("name")
    for alias, route in aliases.items():
        namespaced_match = namespace == route.namespace and name == route.name
        flat_match = namespace is None and name == route.qualified_name
        if namespaced_match or flat_match:
            rewritten["name"] = alias
            rewritten.pop("namespace", None)
            break
    return rewritten


def restore_response_tool_calls(value: Any, aliases: dict[str, ToolRoute]) -> Any:
    if isinstance(value, list):
        return [restore_response_tool_calls(item, aliases) for item in value]
    if not isinstance(value, dict):
        return value

    restored = {key: restore_response_tool_calls(item, aliases) for key, item in value.items()}
    if restored.get("type") != "function_call":
        return restored

    upstream_name = restored.get("name")
    for alias, route in aliases.items():
        if upstream_name in (alias, route.qualified_name):
            restored["name"] = route.name
            restored["namespace"] = route.namespace
            break
    return restored


def rewrite_json_body(body: bytes, aliases: dict[str, ToolRoute]) -> bytes:
    if not aliases:
        return body
    try:
        parsed = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return body
    restored = restore_response_tool_calls(parsed, aliases)
    return json.dumps(restored, separators=(",", ":")).encode("utf-8")


def rewrite_sse_line(line: bytes, aliases: dict[str, ToolRoute]) -> bytes:
    if not aliases or not line.startswith(b"data:"):
        return line
    prefix, raw = line.split(b":", 1)
    stripped = raw.strip()
    if not stripped or stripped == b"[DONE]":
        return line
    rewritten = rewrite_json_body(stripped, aliases)
    ending = b"\n" if line.endswith(b"\n") else b""
    return prefix + b": " + rewritten + ending


def extract_error_message(body: bytes) -> str:
    try:
        parsed = json.loads(body)
        error = parsed.get("error") if isinstance(parsed, dict) else None
        if isinstance(error, dict) and isinstance(error.get("message"), str):
            message = error["message"]
        elif isinstance(parsed, dict) and isinstance(parsed.get("message"), str):
            message = parsed["message"]
        else:
            message = body.decode("utf-8", errors="replace")
    except (json.JSONDecodeError, UnicodeDecodeError):
        message = body.decode("utf-8", errors="replace")

    message = re.sub(r"\s+", " ", message).strip()
    if len(message) > MAX_ERROR_CHARS:
        return message[:MAX_ERROR_CHARS] + "… [upstream error truncated]"
    return message or "Upstream request failed without an error message"
