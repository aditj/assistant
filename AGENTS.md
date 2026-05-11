# AGENTS.md

Voice assistant replacing Google Assistant on Android: a Kotlin app talks to a small FastAPI backend that runs an OpenRouter-driven agent loop with tool calls. Backend lives on Render.

## Stack

- **Backend** — Python 3.13, FastAPI, deployed to Render. Manage with `uv`.
- **Android** — Kotlin, minSdk 29, Gradle 8.10. Uses `HttpURLConnection` (not OkHttp — see "Gotchas").
- **Models** — OpenRouter. `fast_model` (default `anthropic/claude-haiku-4-5`) drives most turns; `smart_model` (default `anthropic/claude-opus-4-7`) on escalation.
- **Auth** — shared secret in `X-API-Key` header.

## Quick commands

```bash
# Backend (local)
cd backend && uv run uvicorn assistant_backend.main:app --reload

# Backend smoke test
curl -X POST localhost:8000/agent \
  -H 'content-type: application/json' \
  -H "X-API-Key: $(grep ASSISTANT_API_KEY backend/.env | cut -d= -f2)" \
  -d '{"transcript":"add milk to my list"}'

# Android build + install (requires device visible to adb)
cd android && JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
  ./gradlew installDebug

# Deploy backend (force, since auto-deploy is slow on public repos)
git push
render deploys create srv-d80l6ejeo5us73fkqg00 \
  --commit $(git rev-parse HEAD) --wait --confirm
```

See `docs/setup.md` for the full first-time walkthrough.

## Code conventions

- **Python**: always `uv` (`uv add`, `uv run`), never `pip` / `venv`. Type hints; prefer `X | None` over `Optional[X]`. FastAPI dependency injection over middleware.
- **Kotlin**: no OkHttp — use `HttpURLConnection` via `Backend.kt`. `kotlinx.coroutines` for async, `JSONObject`/`JSONArray` for JSON.
- **Secrets**: never commit `backend/.env`, `android/local.properties` — both gitignored. The same `ASSISTANT_API_KEY` must be in all three locations (local .env, Render env vars, Android local.properties).
- **Commits**: include `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` when authored by Claude Code.
- **Docs**: don't write `*.md` unless asked. Comments only when WHY is non-obvious.

## Layout

```
backend/src/assistant_backend/
  main.py                       FastAPI app + /agent endpoint + auth
  agent.py                      LLM tool-calling loop, hybrid routing, history trim
  openrouter.py                 OpenAI-compatible HTTP client
  config.py                     pydantic-settings, .env-driven
  bootstrap_google_auth.py      One-shot OAuth flow
  tools/
    registry.py                 Tool schemas + dispatch
    google_tasks.py             Google Tasks API + list-name cache

android/app/src/main/
  AndroidManifest.xml
  kotlin/com/aditjain/assistant/
    AssistantInteractionService.kt          System-bound assistant role
    AssistantInteractionSessionService.kt   Per-invocation session factory
    AssistantSession.kt                     Mic → backend → TTS loop
    MainActivity.kt                         Setup / typed-test UI
    Backend.kt                              HTTP helper
  res/layout/session_overlay.xml            Voice session UI
  res/drawable/dot_*.xml                    Status dots
```

## Adding a new tool

Brief: implement in `backend/src/assistant_backend/tools/<your_tool>.py`, register schema + dispatch entry in `tools/registry.py`. Full walkthrough: `docs/adding-tools.md`.

## Known infra

- **GitHub**: https://github.com/aditj/assistant (public)
- **Render service**: `srv-d80l6ejeo5us73fkqg00` → https://assistant-backend-blx7.onrender.com (starter plan)
- **Phone (dev)**: Pixel 9a, paired via wireless ADB

## Gotchas

- **Render auto-deploy is slow on public repos** without the GitHub app installed. Always force-deploy: `render deploys create ... --wait --confirm`.
- **Render CLI v2.15 cannot update env vars** via `services update`. Use the REST API: `PUT https://api.render.com/v1/services/{id}/env-vars/{key}` with bearer token from `~/.render/cli.yaml`.
- **JAVA_HOME** must point at Android Studio's bundled JDK: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`. The bare command-line `java` may be absent.
- **OkHttp + Kotlin K2 compiler** mismatched metadata in this project. Reverted to `HttpURLConnection`. Don't add OkHttp back without verifying Kotlin/OkHttp versions align.
- **`$status` in zsh is read-only**. Don't use that name when scripting around `render deploys list`.
- **Default TTS** on the test Pixel is Samsung's, not Google's. We explicitly init `TextToSpeech(context, listener, "com.google.android.tts")` to get the neural voices.
- **`RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)`** is a silent no-op. The role exists but isn't user-requestable — open `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` instead.
- **`scope.cancel()` in `onHide`** killed the second invocation of the assistant. Use `scope.coroutineContext.cancelChildren()`.
