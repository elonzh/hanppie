"""FastMCP server exposing the persistent Hanppie Python worker."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass

from mcp.server.fastmcp import Context, FastMCP
from mcp.server.fastmcp.exceptions import ToolError
from mcp.types import ToolAnnotations

from hanppie.mcp.executor import ExecutorConfig, PythonExecutor, RobotAccess

SERVER_INSTRUCTIONS = """Hanppie controls one local DJI RoboMaster S1 through a persistent worker. Call get_python_context before unfamiliar code; it contains the supported robot facade signatures, execution-access modes, version, and log paths. execute_python defaults to robot_access='auto', which reuses or opens a connection. Use robot_access='reuse' to expose only an existing connection and 'none' for host-only Python. Normal calls keep the active App connection but always neutralize and disarm before returning. Direct chassis, gimbal, and infrared code uses the normal robot.arm() and lease API. Velocity duration is not proof of an exact angle or distance; use checkpoint() to retain partial progress. Gel firing is available as robot.fire_gel() or robot.fire('gel') through the persistent LabRobot/Bridge backend. Never expose this trusted local arbitrary-Python tool to untrusted users or the public internet."""


@dataclass
class AppContext:
    executor: PythonExecutor


def create_server(
    config: ExecutorConfig,
    *,
    executor_factory: Callable[[ExecutorConfig], PythonExecutor] = PythonExecutor,
) -> FastMCP:
    """Create a server whose lifespan owns one reusable executor."""

    @asynccontextmanager
    async def lifespan(_: FastMCP) -> AsyncIterator[AppContext]:
        executor = executor_factory(config)
        try:
            yield AppContext(executor=executor)
        finally:
            await asyncio.to_thread(executor.close)

    server = FastMCP(
        "hanppie",
        instructions=SERVER_INSTRUCTIONS,
        lifespan=lifespan,
    )

    @server.tool(
        title="Get Hanppie Python context",
        annotations=ToolAnnotations(readOnlyHint=True, idempotentHint=True),
    )
    def get_python_context(ctx: Context) -> dict[str, object]:
        """Return injected names, capabilities, examples, and target-selection rules."""

        return _executor(ctx).describe_context()

    @server.tool(
        title="Get robot connection status",
        annotations=ToolAnnotations(readOnlyHint=True, idempotentHint=True),
    )
    async def get_connection_status(ctx: Context) -> dict[str, object]:
        """Report whether the persistent worker currently holds an S1 connection."""

        return _checked(await asyncio.to_thread(_executor(ctx).connection_status))

    @server.tool(
        title="Connect RoboMaster S1",
        annotations=ToolAnnotations(readOnlyHint=False, idempotentHint=True),
    )
    async def connect_robot(ctx: Context) -> dict[str, object]:
        """Discover or select the configured S1 and keep its App session connected."""

        return _checked(await asyncio.to_thread(_executor(ctx).connect))

    @server.tool(
        title="Execute trusted Hanppie Python",
        annotations=ToolAnnotations(
            readOnlyHint=False,
            destructiveHint=True,
            idempotentHint=False,
            openWorldHint=True,
        ),
    )
    async def execute_python(
        code: str,
        ctx: Context,
        robot_access: RobotAccess = "auto",
        timeout_seconds: float | None = None,
        connect_robot: bool | None = None,
    ) -> dict[str, object]:
        """Execute trusted Host Python with a robot facade and return result/output/artifacts."""

        return _checked(
            await asyncio.to_thread(
                _executor(ctx).execute,
                code,
                robot_access=robot_access,
                connect_robot=connect_robot,
                timeout_seconds=timeout_seconds,
            )
        )

    @server.tool(
        title="Disconnect RoboMaster S1",
        annotations=ToolAnnotations(
            readOnlyHint=False,
            destructiveHint=False,
            idempotentHint=True,
        ),
    )
    async def disconnect_robot(ctx: Context) -> dict[str, object]:
        """Neutralize, disarm, and close the persistent S1 connection."""

        return _checked(await asyncio.to_thread(_executor(ctx).disconnect))

    return server


def serve(config: ExecutorConfig) -> None:
    """Run the Hanppie MCP server over STDIO."""

    create_server(config).run(transport="stdio")


def _executor(ctx: Context) -> PythonExecutor:
    app_context: AppContext = ctx.request_context.lifespan_context
    return app_context.executor


def _checked(response: dict[str, object]) -> dict[str, object]:
    """Raise a real MCP tool error while retaining detailed local call records."""

    if response.get("ok") is not False:
        return response
    error = response.get("error")
    message: dict[str, object] = {
        "ok": False,
        "error": error if isinstance(error, dict) else {"message": "Hanppie tool failed"},
    }
    for key in ("run_id", "artifact_dir", "events", "connection", "cleanup_errors"):
        if key in response:
            message[key] = response[key]
    raise ToolError(json.dumps(message, ensure_ascii=False, default=str))
