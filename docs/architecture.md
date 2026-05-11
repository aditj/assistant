# Architecture

## High-level flow

```
[Pixel 9a]                         [Render]              [External]

long-press power
   ↓
VoiceInteractionService            FastAPI /agent        OpenRouter
   ↓                                   ↓                 (Haiku → Opus on escalation)
SpeechRecognizer (on-device)       agent loop                 ↓
   ↓ transcript                       ↓ tool calls       tool dispatch
HTTPS POST /agent ────────────────►                          ↓
  X-API-Key, history                                     Google Tasks API
   ↓                                                     (list, insert)
reply text + updated history
   ↓
TextToSpeech (Google neural)
   ↓
auto-resume listening
   ↓
multi-turn until silence
```

## Backend

### Agent loop (`agent.py`)
Each `/agent` request brings a `transcript` and optional `history` (list of OpenAI-format messages including tool results).

- If `history` is empty (new conversation): fetch the user's Google Tasks list names and inject them into the system prompt. This is how the LLM learns valid list names without needing a separate `list_lists` tool call.
- Loop up to `MAX_TURNS` (8):
  1. Send messages to OpenRouter
  2. Model returns a message
  3. If no `tool_calls` → that's the final reply, break
  4. If `tool_calls` → execute each, append `role:tool` results, continue
- `escalate_to_smart_model` is a sentinel tool: when called, the loop flips `model` to `smart_model` for remaining turns. The model itself doesn't see special behavior — it just thinks the tool succeeded.

### Hybrid routing
- `fast_model` (default: `anthropic/claude-haiku-4-5`) — sub-second TTFT, drives most turns.
- `smart_model` (default: `anthropic/claude-opus-4-7`) — used only after explicit `escalate_to_smart_model` call.
- Both configurable via `FAST_MODEL` / `SMART_MODEL` env vars using any OpenRouter model ID.

### History trimming
After each conversation, history is trimmed to ~40 messages while preserving the system prompt at index 0. Avoids unbounded token cost on long voice sessions. The trim also avoids leaving orphan `role:tool` messages without a preceding `tool_calls`.

### Tool dispatch
`tools/registry.py` holds two parallel structures:
- `TOOL_SCHEMAS` — what the LLM sees (OpenAI function-calling format)
- `TOOL_DISPATCH` — name → async impl

The agent loop looks up the impl by name and invokes with the args the model produced. Errors are caught and surfaced to the model as the tool result string, giving the model a chance to recover.

### Google Tasks list cache
`tools/google_tasks.py` caches `list_name (lowercased) → list_id` for 5 minutes to avoid round-trips when the same conversation routes multiple items to the same list. On `create_google_task_list`, the new list is pre-populated in the cache so the next `add_google_task` resolves locally.

## Android

### Session lifecycle
1. `AssistantInteractionService` (extends `VoiceInteractionService`) is registered as the system's default assistant via the manifest.
2. `AssistantInteractionSessionService.onNewSession()` creates a fresh `AssistantSession` per invocation.
3. `AssistantSession`:
   - `onShow` — tears down any leftover I/O, resets history, inits TTS (Google engine, best voice), starts recognizer.
   - `onResults` — sets status to Thinking, calls `Backend.callAgent(transcript, history)`.
   - Reply arrives → updates UI → TTS speaks → `onUtteranceDone` → 300ms delay → resume listening.
   - 6s of silence after entering Listening state, or user tap on overlay → `hide()`.
   - `onHide` — destroys recognizer/TTS, cancels in-flight coroutines via `scope.coroutineContext.cancelChildren()`. **Does not** call `scope.cancel()` — that permanently kills the scope and breaks the next `onShow`.

### Voice selection
The system-default TTS engine on some phones (including the test Pixel 9a) is not Google's. We force `TextToSpeech(context, listener, "com.google.android.tts")`. Then iterate `tts.voices`, filter to `en`, sort by `quality` descending (preferring offline among ties), and `tts.voice = best`.

### Status indicator
The overlay shows a colored dot reflecting state:
- cyan — Listening
- amber — Thinking
- purple — Speaking

### Multi-turn
The `conversation` JSONArray is held by the session and sent to the backend on every turn. The backend returns the updated history; the client replaces its local copy. When the session is dismissed, the array is discarded — there's no cross-session persistence.

## Auth

`X-API-Key` header. The same secret value lives in three places:
- `backend/.env` (local dev)
- Render env vars (production)
- `android/local.properties` → baked into `BuildConfig.API_KEY` at build time

All three must match. None are ever committed.

If `ASSISTANT_API_KEY` is unset on the backend, the auth dependency short-circuits to allow all requests — useful only for local dev. **In production it must be set.**

## Storage

No database. Conversation history is purely in-memory on the client. Once the assistant session is dismissed, the conversation is gone.

Google Tasks is the durable persistence layer; everything the user "remembers" via the assistant lives there.

## Deployment topology

- **GitHub** holds the source. Auto-deploy from push is enabled on Render but **polled** (not webhooked) because the repo is public and we never installed the Render GitHub app. Polling can take 5+ minutes; force-deploy via CLI is the standard workflow.
- **Render** runs the backend. Single starter-plan instance, region `oregon`. No database or cache.
- **OpenRouter** is the LLM provider. Model choice is server-side env config.
- **Google Tasks** is the side-effect target.

This minimizes moving parts: there's no queue, no cache, no DB, no auth provider beyond a shared secret.
