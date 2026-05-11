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
                "Add a task to one of the user's Google Tasks lists. "
                "Use this whenever the user wants to remember, capture, or add a todo."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "What to do — short and imperative.",
                    },
                    "notes": {
                        "type": "string",
                        "description": "Optional extra details.",
                    },
                    "list_name": {
                        "type": "string",
                        "description": (
                            "Name of the target list (case-insensitive). Match to "
                            "the available lists provided in the system prompt. "
                            "Omit to use the user's default list."
                        ),
                    },
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_google_task_list",
            "description": (
                "Create a new Google Tasks list. Use when the user asks to "
                "make/start a new list (e.g. \"create a list called Travel\"). "
                "After creating, you can immediately add tasks to it by name."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Title of the new list (will be used as the list name).",
                    },
                },
                "required": ["name"],
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


async def _add_google_task(
    title: str,
    notes: str | None = None,
    list_name: str | None = None,
) -> str:
    result = await google_tasks.add_task(title, notes, list_name)
    where = f" to {list_name}" if list_name else ""
    return f"OK, added '{result['title']}'{where}."


async def _create_google_task_list(name: str) -> str:
    result = await google_tasks.create_task_list(name)
    return f"Created list '{result['title']}'."


async def _escalate(reason: str) -> str:
    return ESCALATE_SENTINEL


TOOL_DISPATCH: dict[str, ToolFn] = {
    "add_google_task": _add_google_task,
    "create_google_task_list": _create_google_task_list,
    "escalate_to_smart_model": _escalate,
}
