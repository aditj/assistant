import json
import logging
from typing import Any

from .config import settings
from .openrouter import chat_completion
from .tools.registry import ESCALATE_SENTINEL, TOOL_DISPATCH, TOOL_SCHEMAS

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are a voice assistant. Your replies will be spoken aloud.

Rules:
- Keep replies to 1-2 short sentences. No markdown, no lists, no code blocks.
- When the user asks you to do something, use a tool — don't just describe what you would do.
- Confirm completed actions briefly: "Done — added milk to your tasks."
- Don't ask clarifying questions for simple requests. Pick a sensible default and proceed.
- The user may follow up across multiple turns. Keep context — don't re-ask things they already told you.
- If something fails, say so in one sentence."""

MAX_TURNS = 8
# Cap stored history (excluding system msg) to keep token usage bounded.
MAX_HISTORY_MESSAGES = 40


def _trim_history(history: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if len(history) <= MAX_HISTORY_MESSAGES + 1:  # +1 for system
        return history
    system = [m for m in history[:1] if m.get("role") == "system"]
    tail = history[-MAX_HISTORY_MESSAGES:]
    # Avoid starting tail on an orphan tool message (no matching tool_calls upstream)
    while tail and tail[0].get("role") == "tool":
        tail = tail[1:]
    return system + tail


async def run_agent(
    transcript: str,
    history: list[dict[str, Any]] | None = None,
) -> tuple[str, list[dict[str, Any]]]:
    if history:
        messages: list[dict[str, Any]] = list(history)
        if not any(m.get("role") == "system" for m in messages):
            messages.insert(0, {"role": "system", "content": SYSTEM_PROMPT})
    else:
        messages = [{"role": "system", "content": SYSTEM_PROMPT}]
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

    return final_reply, _trim_history(messages)
