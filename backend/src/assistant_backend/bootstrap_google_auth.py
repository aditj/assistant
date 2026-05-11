"""One-time helper to obtain a Google OAuth refresh token for the Tasks API.

Usage:
    cd backend
    uv run python -m assistant_backend.bootstrap_google_auth

Prerequisites:
    1. Create a Google Cloud project: https://console.cloud.google.com
    2. Enable the "Tasks API".
    3. Create OAuth credentials of type "Desktop app".
    4. Put the client id + secret in backend/.env as GOOGLE_CLIENT_ID and
       GOOGLE_CLIENT_SECRET.
    5. Add your Google account as a "test user" on the OAuth consent screen
       (while the app is in testing mode).

This opens a browser, prompts for consent, and prints a refresh token. Paste it
into .env as GOOGLE_REFRESH_TOKEN.
"""
from google_auth_oauthlib.flow import InstalledAppFlow

from .config import settings

SCOPES = ["https://www.googleapis.com/auth/tasks"]


def main() -> None:
    if not settings.google_client_id or not settings.google_client_secret:
        raise SystemExit(
            "Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in .env before running."
        )

    flow = InstalledAppFlow.from_client_config(
        {
            "installed": {
                "client_id": settings.google_client_id,
                "client_secret": settings.google_client_secret,
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token",
                "redirect_uris": ["http://localhost"],
            }
        },
        scopes=SCOPES,
    )
    creds = flow.run_local_server(port=0, prompt="consent", access_type="offline")

    if not creds.refresh_token:
        raise SystemExit(
            "No refresh token returned. Revoke prior consent at "
            "https://myaccount.google.com/permissions and retry."
        )

    print("\n=== GOOGLE_REFRESH_TOKEN ===")
    print(creds.refresh_token)
    print("\nPaste this into backend/.env as GOOGLE_REFRESH_TOKEN.")


if __name__ == "__main__":
    main()
