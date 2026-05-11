import logging
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .agent import run_agent
from .config import settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

app = FastAPI(title="Assistant Backend", version="0.2.0")


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


class AgentResponse(BaseModel):
    reply: str
    history: list[dict[str, Any]]


@app.get("/")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/agent", response_model=AgentResponse, dependencies=[Depends(require_api_key)])
async def agent_endpoint(req: AgentRequest) -> AgentResponse:
    transcript = req.transcript.strip()
    if not transcript:
        raise HTTPException(status_code=400, detail="empty transcript")
    reply, history = await run_agent(transcript, req.history or None)
    return AgentResponse(reply=reply, history=history)
