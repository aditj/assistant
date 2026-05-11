from typing import Any, Awaitable, Callable

from .. import actions
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
            "name": "call_contact",
            "description": (
                "Place a phone call to one of the user's contacts. Use whenever "
                "the user says things like \"call Mom\", \"phone John\", \"ring "
                "Sarah\". The phone will fuzzy-match the name against the user's "
                "address book and dial. The call is dispatched on the phone AFTER "
                "you finish speaking your reply — so confirm in your reply (e.g. "
                "\"Calling Mom now.\")."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": (
                            "Contact name as the user said it. Pass it as-is — the "
                            "phone handles the lookup and any fuzzy matching."
                        ),
                    },
                },
                "required": ["name"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "send_whatsapp",
            "description": (
                "Send a WhatsApp message to one of the user's contacts. Use whenever "
                "the user says things like \"text X on WhatsApp\", \"WhatsApp X saying "
                "...\", \"reply to X on WhatsApp\", or follow-ups to a notification you "
                "just summarized. Two delivery paths handled by the phone:\n"
                "  - If a recent WhatsApp notification from this contact is still in "
                "    the cache, the message is sent silently via the notification's "
                "    quick-reply action.\n"
                "  - Otherwise WhatsApp opens with the text pre-filled and the user "
                "    must tap send (Android doesn't allow fully silent third-party "
                "    sending). Mention this in your reply so they know to tap."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "contact": {
                        "type": "string",
                        "description": "Contact name as the user said it — the phone fuzzy-matches.",
                    },
                    "text": {
                        "type": "string",
                        "description": "Message body, exactly as dictated by the user.",
                    },
                },
                "required": ["contact", "text"],
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


async def _call_contact(name: str) -> str:
    actions.queue({"type": "call", "name": name})
    return f"OK, dialing {name} now."


async def _send_whatsapp(contact: str, text: str) -> str:
    actions.queue({"type": "whatsapp_send", "contact": contact, "text": text})
    return f"OK, queued WhatsApp to {contact}: {text!r}"


async def _escalate(reason: str) -> str:
    return ESCALATE_SENTINEL


TOOL_DISPATCH: dict[str, ToolFn] = {
    "add_google_task": _add_google_task,
    "create_google_task_list": _create_google_task_list,
    "call_contact": _call_contact,
    "send_whatsapp": _send_whatsapp,
    "escalate_to_smart_model": _escalate,
}
