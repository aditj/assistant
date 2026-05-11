import json
import logging
from typing import Any

from . import actions
from .config import settings
from .openrouter import chat_completion
from .tools import google_tasks
from .tools.registry import ESCALATE_SENTINEL, TOOL_DISPATCH, TOOL_SCHEMAS

log = logging.getLogger(__name__)

BASE_SYSTEM_PROMPT = """You are a voice assistant. Your replies will be spoken aloud.

Rules:
- Keep replies to 1-2 short sentences. No markdown, no lists, no code blocks.
- When the user asks you to do something, use a tool — don't just describe what you would do.
- Confirm completed actions briefly: "Done — added milk to your groceries list."
- Don't ask clarifying questions for simple requests. Pick a sensible default and proceed.
- The user may follow up across multiple turns. Keep context — don't re-ask things they already told you.
- If something fails, say so in one sentence.
- For phone-side actions (calls, etc.), confirm the action in your reply; the phone executes it after you finish speaking."""

MAX_TURNS = 8
MAX_HISTORY_MESSAGES = 40
MAX_NOTIFICATIONS_IN_PROMPT = 20


def _build_system_prompt(
    list_names: list[str],
    notifications: list[dict[str, Any]],
) -> str:
    base = BASE_SYSTEM_PROMPT
    if list_names:
        pretty = ", ".join(f'"{n}"' for n in list_names)
        base += (
            "\n\nAvailable Google Tasks lists: "
            + pretty
            + ". When adding a task, match the user's wording (e.g. \"add to groceries\","
              " \"work list\") to one of these names exactly and pass it as list_name."
              " If they don't mention a list, omit list_name."
        )

    # Always declare notification capability so the model never confabulates
    # "I don't have access" when the array is simply empty.
    base += "\n\nNotification access: you CAN see the user's recent phone notifications."
    if notifications:
        lines = []
        for n in notifications[:MAX_NOTIFICATIONS_IN_PROMPT]:
            app = str(n.get("app", "")).strip() or "?"
            title = str(n.get("title", "")).strip()
            text = str(n.get("text", "")).strip()
            body = " — ".join(p for p in (title, text) if p) or "(no body)"
            lines.append(f"- [{app}] {body}")
        base += (
            f" Right now there are {len(notifications)} cached (most recent first):\n"
            + "\n".join(lines)
            + "\n\nIf the user asks about notifications, messages, or what's new,"
              " summarize from this list. Don't bring them up unprompted."
        )
    else:
        base += (
            " Right now the cache is empty (no recent notifications captured)."
            " If the user asks, say \"You have no recent notifications.\" — do NOT"
            " say you can't see them."
        )
    return base


def _trim_history(history: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if len(history) <= MAX_HISTORY_MESSAGES + 1:
        return history
    system = [m for m in history[:1] if m.get("role") == "system"]
    tail = history[-MAX_HISTORY_MESSAGES:]
    while tail and tail[0].get("role") == "tool":
        tail = tail[1:]
    return system + tail


async def run_agent(
    transcript: str,
    history: list[dict[str, Any]] | None = None,
    notifications: list[dict[str, Any]] | None = None,
) -> tuple[str, list[dict[str, Any]], list[dict[str, Any]]]:
    actions_list = actions.begin()

    if history:
        messages: list[dict[str, Any]] = list(history)
        if not any(m.get("role") == "system" for m in messages):
            messages.insert(0, {"role": "system", "content": BASE_SYSTEM_PROMPT})
    else:
        try:
            lists = await google_tasks.list_task_lists()
        except Exception:
            log.exception("failed to fetch task lists; continuing without them")
            lists = []
        messages = [{
            "role": "system",
            "content": _build_system_prompt(lists, notifications or []),
        }]
    messages.append({"role": "user", "content": transcript})

    model = settings.fast_model
    final_reply = ""

    for turn in range(MAX_TURNS):
        log.info("agent turn=%d model=%s msgs=%d", turn, model, len(messages))
        response = await chat_completion(model=model, messages=messages, tools=TOOL_SCHEMAS)
        msg = response["choices"][0]["message"]
        messages.append(msg)

        tool_calls = msg.get("tool_calls") or []
        if not tool_calls:
            final_reply = msg.get("content") or ""
            break

        for call in tool_calls:
            name = call["function"]["name"]
            try:
                args = json.loads(call["function"].get("arguments") or "{}")
            except json.JSONDecodeError:
                args = {}

            fn = TOOL_DISPATCH.get(name)
            if fn is None:
                result = f"Error: unknown tool '{name}'"
            else:
                try:
                    result = await fn(**args)
                except Exception as e:
                    log.exception("tool %s failed", name)
                    result = f"Error running {name}: {e}"

            if result == ESCALATE_SENTINEL:
                model = settings.smart_model
                result = "Escalated. Proceed with the user's request now."

            messages.append({
                "role": "tool",
                "tool_call_id": call["id"],
                "content": str(result),
            })
    else:
        final_reply = "Sorry, I got stuck. Try rephrasing."
        messages.append({"role": "assistant", "content": final_reply})

    return final_reply, _trim_history(messages), actions_list
