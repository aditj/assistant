import asyncio
import time
from typing import Any

from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

from ..config import settings

GOOGLE_TASKS_SCOPES = ["https://www.googleapis.com/auth/tasks"]

# Cache: list-name (lowercased) -> (list_id, expiry_epoch)
_list_cache: dict[str, tuple[str, float]] = {}
_LIST_CACHE_TTL = 300.0


def _credentials() -> Credentials:
    if not settings.google_refresh_token:
        raise RuntimeError(
            "GOOGLE_REFRESH_TOKEN not set. Run: "
            "uv run python -m assistant_backend.bootstrap_google_auth"
        )
    return Credentials(
        token=None,
        refresh_token=settings.google_refresh_token,
        token_uri="https://oauth2.googleapis.com/token",
        client_id=settings.google_client_id,
        client_secret=settings.google_client_secret,
        scopes=GOOGLE_TASKS_SCOPES,
    )


def _service() -> Any:
    return build("tasks", "v1", credentials=_credentials(), cache_discovery=False)


def _list_lists_sync() -> list[dict[str, Any]]:
    return _service().tasklists().list().execute().get("items", [])


def _refresh_list_cache_sync() -> list[dict[str, Any]]:
    items = _list_lists_sync()
    expiry = time.time() + _LIST_CACHE_TTL
    _list_cache.clear()
    for tl in items:
        _list_cache[tl["title"].lower()] = (tl["id"], expiry)
    return items


def _resolve_list_id_sync(name: str | None) -> str:
    if not name:
        return "@default"
    key = name.lower().strip()
    cached = _list_cache.get(key)
    if cached and cached[1] > time.time():
        return cached[0]
    _refresh_list_cache_sync()
    cached = _list_cache.get(key)
    return cached[0] if cached else "@default"


def _add_task_sync(title: str, notes: str | None, list_name: str | None) -> dict[str, Any]:
    list_id = _resolve_list_id_sync(list_name)
    body: dict[str, Any] = {"title": title}
    if notes:
        body["notes"] = notes
    return _service().tasks().insert(tasklist=list_id, body=body).execute()


async def add_task(
    title: str,
    notes: str | None = None,
    list_name: str | None = None,
) -> dict[str, Any]:
    return await asyncio.to_thread(_add_task_sync, title, notes, list_name)


async def list_task_lists() -> list[str]:
    """Return the user's Google Tasks list titles."""
    items = await asyncio.to_thread(_refresh_list_cache_sync)
    return [tl["title"] for tl in items]
