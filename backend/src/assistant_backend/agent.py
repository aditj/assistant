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
- If something fails, say so in one sentence."""

MAX_TURNS = 8


async def run_agent(transcript: str) -> str:
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": transcript},
    ]
    model = settings.fast_model

    for turn in range(MAX_TURNS):
        log.info("agent turn=%d model=%s", turn, model)
        response = await chat_completion(model=model, messages=messages, tools=TOOL_SCHEMAS)
        msg = response["choices"][0]["message"]
        messages.append(msg)

        tool_calls = msg.get("tool_calls") or []
        if not tool_calls:
            return msg.get("content") or ""

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

    return "Sorry, I got stuck. Try rephrasing."
