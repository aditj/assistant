"""Ambient per-request collector for phone-side actions.

The agent loop runs tool calls server-side. But some tools (call a contact,
send an SMS, open an app, toggle a system setting) can only be executed on
the phone. Such tools record an action descriptor here; the endpoint returns
the collected list to the client to dispatch after TTS finishes.

Using contextvars keeps tool signatures clean — tools that don't need this
don't have to know about it. FastAPI runs each request in its own asyncio
task with its own context, so there's no cross-request leakage.
"""
import contextvars
from typing import Any

_actions: contextvars.ContextVar[list[dict[str, Any]] | None] = contextvars.ContextVar(
    "assistant_actions", default=None
)


def begin() -> list[dict[str, Any]]:
    """Start a fresh action collector for the current request. Returns the list
    so the caller (agent loop) can read it after running."""
    collector: list[dict[str, Any]] = []
    _actions.set(collector)
    return collector


def queue(action: dict[str, Any]) -> None:
    """Append a phone-side action to the current request's collector.
    No-op if called outside `begin()` (e.g. from a test)."""
    collector = _actions.get()
    if collector is not None:
        collector.append(action)
