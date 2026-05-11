import asyncio
from typing import Any

from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

from ..config import settings

GOOGLE_TASKS_SCOPES = ["https://www.googleapis.com/auth/tasks"]


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


def _add_task_sync(title: str, notes: str | None) -> dict[str, Any]:
    service = build("tasks", "v1", credentials=_credentials(), cache_discovery=False)
    body: dict[str, Any] = {"title": title}
    if notes:
        body["notes"] = notes
    return service.tasks().insert(tasklist="@default", body=body).execute()


async def add_task(title: str, notes: str | None = None) -> dict[str, Any]:
    return await asyncio.to_thread(_add_task_sync, title, notes)
