import logging

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from .agent import run_agent

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

app = FastAPI(title="Assistant Backend", version="0.1.0")


class AgentRequest(BaseModel):
    transcript: str


class AgentResponse(BaseModel):
    reply: str


@app.get("/")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/agent", response_model=AgentResponse)
async def agent_endpoint(req: AgentRequest) -> AgentResponse:
    transcript = req.transcript.strip()
    if not transcript:
        raise HTTPException(status_code=400, detail="empty transcript")
    reply = await run_agent(transcript)
    return AgentResponse(reply=reply)
