# Setup

First-time walkthrough from a fresh clone to a working voice assistant on your phone. macOS-targeted; Linux works with path tweaks.

## Prerequisites

```bash
brew install uv gh render
# Android Studio: https://developer.android.com/studio
```

## 1. Clone and install backend deps

```bash
git clone https://github.com/aditj/assistant
cd assistant/backend
uv sync
```

## 2. Get keys

### OpenRouter
1. Sign up at https://openrouter.ai
2. Generate a key at https://openrouter.ai/keys
3. Add credits (~$0.001 per voice turn with Haiku 4.5)

### Google Cloud (Tasks API)
1. Create a project at https://console.cloud.google.com
2. APIs & Services → Library → enable **Tasks API**
3. APIs & Services → Credentials → **Create credentials → OAuth client ID → Desktop app**
4. APIs & Services → OAuth consent screen → add your Google account as a **Test user** while in testing mode
5. Note the client ID and client secret

### Shared secret
```bash
openssl rand -hex 32
```

## 3. Configure backend

```bash
cd backend
cp .env.example .env
# Edit .env with all values from step 2:
#   OPENROUTER_API_KEY=...
#   GOOGLE_CLIENT_ID=...
#   GOOGLE_CLIENT_SECRET=...
#   ASSISTANT_API_KEY=<shared secret>
```

Bootstrap the Google refresh token (one-time):

```bash
uv run python -m assistant_backend.bootstrap_google_auth
```

Browser opens, you consent, the script prints a refresh token. Paste into `.env` as `GOOGLE_REFRESH_TOKEN=...`.

## 4. Smoke test locally

```bash
uv run uvicorn assistant_backend.main:app --reload --port 8000
```

In another shell:
```bash
curl -X POST localhost:8000/agent \
  -H 'content-type: application/json' \
  -H "X-API-Key: $(grep ASSISTANT_API_KEY .env | cut -d= -f2)" \
  -d '{"transcript":"add coffee to my list"}'
```

Should respond with a JSON `reply` plus `history`, and a real entry in Google Tasks.

## 5. Deploy backend to Render

```bash
render login          # opens browser
render workspaces     # find your workspace ID
render workspace set <workspace-id>
```

Create the service (one-time, all flags required since CLI runs non-interactive):

```bash
set -a && source backend/.env && set +a
render services create \
  --name assistant-backend \
  --type web_service \
  --runtime python \
  --repo https://github.com/<you>/assistant \
  --branch main \
  --root-directory backend \
  --build-command "pip install uv && uv sync --frozen --no-dev" \
  --start-command 'uv run uvicorn assistant_backend.main:app --host 0.0.0.0 --port $PORT' \
  --plan starter \
  --region oregon \
  --env-var "OPENROUTER_API_KEY=$OPENROUTER_API_KEY" \
  --env-var "GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID" \
  --env-var "GOOGLE_CLIENT_SECRET=$GOOGLE_CLIENT_SECRET" \
  --env-var "GOOGLE_REFRESH_TOKEN=$GOOGLE_REFRESH_TOKEN" \
  --env-var "ASSISTANT_API_KEY=$ASSISTANT_API_KEY" \
  --output json
```

Note the returned `id` (`srv-...`) and `serviceDetails.url`. The repo must be **public** for Render to read it without the GitHub app installed.

For subsequent deploys after `git push`:

```bash
render deploys create <service-id> --commit $(git rev-parse HEAD) --wait --confirm
```

## 6. Android setup

### local.properties
Create `android/local.properties`:
```
ASSISTANT_API_KEY=<same shared secret>
# Android Studio adds sdk.dir=... on first sync
```

### Build via CLI (no Android Studio needed for build itself)

```bash
cd android
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home

# First time only: generate the gradle wrapper (uses brew-installed gradle 9.x to bootstrap an 8.10 wrapper)
gradle wrapper --gradle-version 8.10

# Build and install on connected device
./gradlew installDebug -PBACKEND_URL=https://<your-service>.onrender.com
```

### Wireless ADB
On phone: Settings → About phone → tap **Build number** 7× → Developer options → **Wireless debugging** → **Pair device with pairing code**.

In Android Studio: Device Manager → **Pair Devices Using Wi-Fi** → enter the IP:port + 6-digit code.

### Set as default assistant
Open the app → tap **Set as default assistant** → in the Settings screen that opens, tap **Digital assistant app** → pick this app.

### Invoke
Long-press power. Speak. Multi-turn until ~6s of silence dismisses.

## Troubleshooting

- **"Unable to locate a Java Runtime"** during gradle build → `export JAVA_HOME=...` to Android Studio's bundled JDK (path above).
- **Gradle wrapper fails downloading**: `brew install gradle` (any version), then `gradle wrapper --gradle-version 8.10`.
- **HTTP 401 from backend** → `X-API-Key` mismatch. Check that `backend/.env`, Render env vars, and `android/local.properties` all have the same `ASSISTANT_API_KEY`.
- **"Sorry I didn't catch that" fires when you did speak** → already fixed in `AssistantSession.kt`; ignore stale `onError(NO_MATCH)` after results arrive.
- **TTS sounds robotic** → make sure Google TTS is installed: `adb shell pm list packages | grep tts`. Should include `com.google.android.tts`.
