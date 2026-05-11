import logging
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .agent import run_agent
from .config import settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

app = FastAPI(title="Assistant Backend", version="0.3.0")


async def require_api_key(
    x_api_key: Annotated[str | None, Header(alias="X-API-Key")] = None,
) -> None:
    expected = settings.assistant_api_key
    if not expected:
        return
    if x_api_key != expected:
        raise HTTPException(status_code=401, detail="invalid or missing X-API-Key")


class AgentRequest(BaseModel):
    transcript: str
    history: list[dict[str, Any]] = Field(default_factory=list)
    # Recent notifications snapshot from the phone. Each entry should have
    # app/title/text/ts fields; extra keys are tolerated.
    notifications: list[dict[str, Any]] = Field(default_factory=list)


class AgentResponse(BaseModel):
    reply: str
    history: list[dict[str, Any]]
    # Phone-side actions the client must execute after TTS finishes
    # (e.g. {"type": "call", "name": "Mom"}).
    actions: list[dict[str, Any]] = Field(default_factory=list)


@app.get("/")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/agent", response_model=AgentResponse, dependencies=[Depends(require_api_key)])
async def agent_endpoint(req: AgentRequest) -> AgentResponse:
    transcript = req.transcript.strip()
    if not transcript:
        raise HTTPException(status_code=400, detail="empty transcript")
    reply, history, actions = await run_agent(
        transcript=transcript,
        history=req.history or None,
        notifications=req.notifications or None,
    )
    return AgentResponse(reply=reply, history=history, actions=actions)
