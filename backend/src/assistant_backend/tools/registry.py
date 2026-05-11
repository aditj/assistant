from typing import Any, Awaitable, Callable

from . import google_tasks

ToolFn = Callable[..., Awaitable[str]]

ESCALATE_SENTINEL = "__ESCALATE__"

TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "add_google_task",
            "description": (
                "Add a task to the user's Google Tasks default list. "
                "Use this whenever the user wants to remember, capture, or add a todo."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "What to do — keep it short and imperative.",
                    },
                    "notes": {
                        "type": "string",
                        "description": "Optional extra details.",
                    },
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "escalate_to_smart_model",
            "description": (
                "Hand the conversation off to a smarter, slower model. "
                "Use this ONLY when the request needs multi-step reasoning or planning "
                "that you cannot do well. Do not use for simple tool calls."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "reason": {"type": "string", "description": "Why escalate."},
                },
                "required": ["reason"],
            },
        },
    },
]


async def _add_google_task(title: str, notes: str | None = None) -> str:
    result = await google_tasks.add_task(title, notes)
    return f"OK, added '{result['title']}' to your tasks."


async def _escalate(reason: str) -> str:
    return ESCALATE_SENTINEL


TOOL_DISPATCH: dict[str, ToolFn] = {
    "add_google_task": _add_google_task,
    "escalate_to_smart_model": _escalate,
}
