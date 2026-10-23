# 사용: uv run --no-project --with "a2a-sdk[http-server]" --with uvicorn python scripts/a2a-echo.py
# 등록: 웹 UI 에이전트 탭 → A2A 에이전트 → http://127.0.0.1:9911 (0.3 방식은 http://127.0.0.1:9911/v03.json)
"""Vesta A2A 연동 확인용 echo 에이전트 (공식 a2a-sdk). 받은 글을 거꾸로 + 글자 수로 답한다."""
import asyncio
import uvicorn
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route
from a2a.types import AgentCard, AgentCapabilities, AgentInterface, AgentSkill
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
from a2a.helpers.proto_helpers import new_task_from_user_message, new_text_part, new_text_message

PORT = 9911

class Echo(AgentExecutor):
    async def execute(self, context: RequestContext, event_queue):
        text = context.get_user_input()
        task = context.current_task or new_task_from_user_message(context.message)
        await event_queue.enqueue_event(task)
        up = TaskUpdater(event_queue, task.id, task.context_id)
        await up.start_work(up.new_agent_message([new_text_part("뒤집는 중")]))
        await asyncio.sleep(0.3)
        await up.add_artifact([new_text_part(f"거꾸로: {text[::-1]}")], name="echo")
        await up.add_artifact([new_text_part(f"글자 수: {len(text)}")], name="count")
        await up.complete()

    async def cancel(self, context, event_queue):
        pass

card = AgentCard(
    name="Echo Agent", description="받은 글을 거꾸로 돌려준다 (Vesta 확인용)", version="0.1",
    supported_interfaces=[AgentInterface(url=f"http://127.0.0.1:{PORT}/a2a", protocol_binding="JSONRPC", protocol_version="1.0")],
    capabilities=AgentCapabilities(streaming=True),
    default_input_modes=["text/plain"], default_output_modes=["text/plain"],
    skills=[AgentSkill(id="reverse", name="Reverse", description="글 뒤집기", tags=["text"])],
)
handler = DefaultRequestHandler(agent_executor=Echo(), task_store=InMemoryTaskStore(), agent_card=card)

async def v03(_):
    return JSONResponse({"name": "Echo Agent (0.3)", "description": "같은 에이전트, 0.3 방식", "url": f"http://127.0.0.1:{PORT}/a2a",
                         "protocolVersion": "0.3.0", "preferredTransport": "JSONRPC", "capabilities": {"streaming": False}, "skills": []})

app = Starlette(routes=create_agent_card_routes(card) + create_jsonrpc_routes(handler, "/a2a", enable_v0_3_compat=True) + [Route("/v03.json", v03)])
uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
