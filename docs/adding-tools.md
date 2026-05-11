# Adding a new tool

A "tool" is an action the LLM can take on behalf of the user. Adding one is three to four steps.

## 1. Implement

Create `backend/src/assistant_backend/tools/<your_tool>.py`:

```python
import asyncio
from typing import Any

def _do_thing_sync(arg: str) -> str:
    # The actual work — typically an HTTP call or library call.
    return f"did {arg}"

async def do_thing(arg: str) -> str:
    # Wrap sync work in a thread so it doesn't block the event loop.
    return await asyncio.to_thread(_do_thing_sync, arg)
```

If your tool needs OAuth or another long-lived credential, prefer storing a refresh token in `.env` (see `google_tasks.py` for the pattern). Add the corresponding field to `config.py`.

## 2. Register schema + dispatch

In `backend/src/assistant_backend/tools/registry.py`:

```python
from . import your_tool

TOOL_SCHEMAS.append({
    "type": "function",
    "function": {
        "name": "do_thing",
        "description": "What this tool does, and crucially WHEN the model should call it.",
        "parameters": {
            "type": "object",
            "properties": {
                "arg": {
                    "type": "string",
                    "description": "What the model should pass.",
                },
            },
            "required": ["arg"],
        },
    },
})

async def _do_thing(arg: str) -> str:
    return await your_tool.do_thing(arg)

TOOL_DISPATCH["do_thing"] = _do_thing
```

## 3. (Optional) Inject context into the system prompt

If the model needs prior knowledge of the tool's environment before deciding to call it — e.g., a list of valid values, paths, channels — fetch that at conversation start in `agent.py` and inject into the system prompt.

`_build_system_prompt(list_names)` in `agent.py` shows the pattern: it runs only when history is empty (new conversation), so the cost is amortized across the whole session.

## 4. Test locally

```bash
cd backend
uv run uvicorn assistant_backend.main:app --reload

# In another shell:
curl -X POST localhost:8000/agent \
  -H 'content-type: application/json' \
  -H "X-API-Key: $(grep ASSISTANT_API_KEY .env | cut -d= -f2)" \
  -d '{"transcript":"do the thing with foo"}'
```

The model should invoke `do_thing(arg="foo")` and reply with the tool's result wrapped in a natural sentence.

## 5. Deploy

```bash
git add backend/src/assistant_backend/tools/ backend/src/assistant_backend/agent.py
git commit -m "Add do_thing tool"
git push
render deploys create srv-d80l6ejeo5us73fkqg00 --commit $(git rev-parse HEAD) --wait --confirm
```

No Android changes needed — tools are entirely backend-side.

## Writing good tool descriptions

The `description` field is the only context the model has for **when** to use the tool. The same model might have hundreds of tools available across deployments; yours has to compete on description quality.

- Bad: `"Search the web."`
- Good: `"Search the web for current information. Use when the user asks about recent news, prices, weather, or anything that may have changed since training."`

- Bad: `"Add a task."`
- Good: `"Add a task to one of the user's Google Tasks lists. Use whenever the user wants to remember, capture, or add a todo."`

Same for parameter descriptions — be explicit about formats, units, valid values.

## Returning helpful tool results

Tool results become `role:tool` messages with a string `content`. The model reads these and uses them to decide what to say next.

- Confirm what happened: `"OK, added 'buy milk' to your Groceries list."`
- Or surface useful state for the model to relay: `"You have 5 tasks in Groceries: buy milk, eggs, bread, coffee, oats."`
- On error, be specific so the model can apologize/retry meaningfully: `"Error: list 'Travel' not found. Existing lists: Groceries, Work, Personal."`

The dispatcher wraps with try/except and surfaces exceptions as `f"Error running {name}: {e}"`. That's fine for unexpected failures but you'll get better UX by catching specific errors in the tool impl and returning structured messages.

## Examples in this repo

- **`add_google_task`** — single side-effect with a confirmation. The 80% case.
- **`create_google_task_list`** — creates a resource the model may want to use immediately afterward. We pre-populate the list-name cache so the very next `add_google_task` call resolves locally.
- **`escalate_to_smart_model`** — a sentinel tool that does no real work but signals the agent loop to swap models. Pattern for protocol-level tools that affect the loop itself.

## Things to avoid

- **Too many required params**. Mark only what you can't proceed without; optional params let the model call with less and still get useful behavior.
- **Tools that produce side effects on read**. If you have something that just queries state, give it a name like `get_*` or `list_*` so the model doesn't think calling it is destructive.
- **Hidden state across calls**. If your tool's behavior depends on call order, surface that in the description, or return state in the result for the model to thread through.
- **Network-flaky tools without timeouts**. Wrap in `asyncio.wait_for` or set httpx/requests timeouts; otherwise one slow tool can stall the whole agent loop past Render's 30s request timeout.
