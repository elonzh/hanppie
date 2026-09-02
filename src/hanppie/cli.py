"""Typer/Rich command-line entry point for Hanppie."""

from __future__ import annotations

import json
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Annotated

import click
import typer
from rich.console import Console
from rich.prompt import Confirm, Prompt

from hanppie import __version__
from hanppie.diagnosis import (
    DEFAULT_CHECKS,
    DiagnosisConfig,
    normalize_check_names,
    render_check_catalog,
    run_diagnosis,
)
from hanppie.mcp.executor import ExecutorConfig
from hanppie.mcp.install import build_server_entry, install_codex_server
from hanppie.mcp.server import serve

app = typer.Typer(
    name="hanppie",
    help="保存、研究和编程控制 DJI RoboMaster S1。",
    no_args_is_help=True,
    rich_markup_mode="rich",
    pretty_exceptions_enable=False,
)
console = Console()
mcp_app = typer.Typer(
    help="运行持久 S1 MCP 服务，并配置 Codex 客户端。",
    no_args_is_help=True,
    rich_markup_mode="rich",
)
app.add_typer(mcp_app, name="mcp")


def _version_callback(value: bool) -> None:
    if value:
        typer.echo(__version__)
        raise typer.Exit()


@app.callback()
def root(
    version: Annotated[
        bool,
        typer.Option("--version", callback=_version_callback, is_eager=True, help="显示版本并退出"),
    ] = False,
) -> None:
    """Hanppie 的命令行入口。"""


@mcp_app.command("serve")
def mcp_serve_command(
    robot_ip: Annotated[str | None, typer.Option(help="显式 S1 IPv4 地址")] = None,
    appid: Annotated[str | None, typer.Option(help="显式 8 位十六进制 AppID")] = None,
    local_ip: Annotated[str, typer.Option(help="本机 IPv4 绑定地址")] = "0.0.0.0",
    discovery_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="自动发现 S1 的广播监听时长（秒）"),
    ] = 4.0,
    connection_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="S1 连接超时（秒）"),
    ] = 10.0,
    execution_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="默认 Python 执行超时（秒）"),
    ] = 20.0,
    max_execution_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="工具调用可请求的最大执行超时（秒）"),
    ] = 60.0,
    artifact_dir: Annotated[
        Path,
        typer.Option(help="MCP 日志、调用记录和 Python 制品的根目录"),
    ] = Path(".hanppie/mcp"),
    debug: Annotated[bool, typer.Option(help="捕获 App 协议调试输出")] = False,
) -> None:
    """通过 STDIO 运行 MCP, 同一服务生命周期复用一个 S1 连接。"""

    try:
        config = ExecutorConfig(
            robot_ip=robot_ip,
            appid=appid,
            local_ip=local_ip,
            discovery_timeout=discovery_timeout,
            connection_timeout=connection_timeout,
            execution_timeout=execution_timeout,
            max_execution_timeout=max_execution_timeout,
            artifact_base=artifact_dir,
            debug=debug,
        )
    except ValueError as exc:
        raise typer.BadParameter(str(exc)) from exc
    serve(config)


@mcp_app.command("install")
def mcp_install_command(
    scope: Annotated[
        str,
        typer.Option(help="配置范围：user 写共享配置，project 写当前项目配置"),
    ] = "user",
    project_dir: Annotated[
        Path | None,
        typer.Option(help="project 范围的起始目录；默认当前目录并向上查找项目根"),
    ] = None,
    codex_home: Annotated[
        Path | None,
        typer.Option(help="user 范围的 Codex 配置目录；默认 CODEX_HOME 或 ~/.codex"),
    ] = None,
    replace: Annotated[
        bool,
        typer.Option(help="只替换已有的 mcp_servers.hanppie 表"),
    ] = False,
    robot_ip: Annotated[str | None, typer.Option(help="显式 S1 IPv4 地址")] = None,
    appid: Annotated[str | None, typer.Option(help="显式 8 位十六进制 AppID")] = None,
    local_ip: Annotated[str, typer.Option(help="本机 IPv4 绑定地址")] = "0.0.0.0",
    discovery_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="自动发现 S1 的广播监听时长（秒）"),
    ] = 4.0,
    connection_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="S1 连接超时（秒）"),
    ] = 10.0,
    execution_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="默认 Python 执行超时（秒）"),
    ] = 20.0,
    max_execution_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="工具调用可请求的最大执行超时（秒）"),
    ] = 60.0,
    artifact_dir: Annotated[
        Path | None,
        typer.Option(help="固定 MCP 数据目录；默认由 Codex 当前项目使用 .hanppie/mcp"),
    ] = None,
    debug: Annotated[bool, typer.Option(help="捕获 App 协议调试输出")] = False,
) -> None:
    """幂等配置 Windows、macOS、Linux/WSL 上共享的 Codex MCP。"""

    if scope not in {"user", "project"}:
        raise typer.BadParameter("scope must be 'user' or 'project'", param_hint="--scope")
    try:
        config = ExecutorConfig(
            robot_ip=robot_ip,
            appid=appid,
            local_ip=local_ip,
            discovery_timeout=discovery_timeout,
            connection_timeout=connection_timeout,
            execution_timeout=execution_timeout,
            max_execution_timeout=max_execution_timeout,
            artifact_base=artifact_dir or Path(".hanppie/mcp"),
            debug=debug,
        )
        serve_args = _installed_serve_args(config, artifact_dir=artifact_dir)
        entry = build_server_entry(
            serve_args,
            tool_timeout_seconds=max_execution_timeout + 5,
        )
        response = install_codex_server(
            entry,
            scope=scope,  # type: ignore[arg-type]
            project_dir=project_dir,
            codex_home=codex_home,
            replace=replace,
        )
    except (OSError, ValueError) as exc:
        raise typer.BadParameter(str(exc)) from exc
    typer.echo(json.dumps(response, ensure_ascii=False, indent=2))


def _installed_serve_args(
    config: ExecutorConfig,
    *,
    artifact_dir: Path | None,
) -> list[str]:
    args = [
        "--local-ip",
        config.local_ip,
        "--discovery-timeout",
        str(config.discovery_timeout),
        "--connection-timeout",
        str(config.connection_timeout),
        "--execution-timeout",
        str(config.execution_timeout),
        "--max-execution-timeout",
        str(config.max_execution_timeout),
    ]
    if config.robot_ip:
        args.extend(("--robot-ip", config.robot_ip))
    if config.appid:
        args.extend(("--appid", config.appid))
    if artifact_dir is not None:
        args.extend(("--artifact-dir", str(artifact_dir.expanduser().resolve())))
    if config.debug:
        args.append("--debug")
    return args


def _interactive_selection() -> tuple[str, ...]:
    render_check_catalog(console)
    answer = Prompt.ask(
        "选择诊断项目（逗号分隔，或输入 standard/all）",
        default="standard",
    ).strip()
    if answer.lower() == "standard":
        return DEFAULT_CHECKS
    return normalize_check_names([answer], all_checks=answer.lower() == "all")


@app.command("diag")
def diag_command(
    checks: Annotated[
        list[str] | None,
        typer.Option(
            "--check",
            "-c",
            help="诊断项目；可重复或使用逗号分隔，也可填写 all",
        ),
    ] = None,
    all_checks: Annotated[bool, typer.Option("--all", help="选择全部诊断项目")] = False,
    interactive: Annotated[
        bool | None,
        typer.Option(
            "--interactive/--no-interactive",
            help="交互选择项目并确认风险；默认根据终端自动判断",
        ),
    ] = None,
    list_checks: Annotated[
        bool,
        typer.Option("--list", help="列出诊断项目并退出"),
    ] = False,
    robot_ip: Annotated[str | None, typer.Option(help="显式 S1 IPv4 地址")] = None,
    appid: Annotated[str | None, typer.Option(help="显式 8 位十六进制 AppID")] = None,
    output_dir: Annotated[
        Path,
        typer.Option(help="诊断日志和报告的父目录"),
    ] = Path(".hanppie/diagnosis"),
    timeout: Annotated[float, typer.Option(min=0.1, help="单项网络等待超时（秒）")] = 10.0,
    discovery_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="App 广播监听时长（秒）"),
    ] = 4.0,
    allow_motion: Annotated[
        bool,
        typer.Option(help="允许底盘、云台和失联停止的低速运动测试"),
    ] = False,
    allow_infrared: Annotated[
        bool,
        typer.Option(help="允许发射一次红外信号"),
    ] = False,
    allow_gel: Annotated[
        bool,
        typer.Option(help="允许空弹仓触发一次水弹发射；必须确认射界安全"),
    ] = False,
    adb: Annotated[str, typer.Option(help="ADB 可执行文件")] = "adb",
    debug: Annotated[bool, typer.Option(help="输出 App/Lab 底层调试信息")] = False,
) -> None:
    """执行一次完整、可重复、带安全清理的 S1 实机诊断。"""

    if list_checks:
        render_check_catalog(console)
        return
    use_interactive = sys.stdin.isatty() if interactive is None else interactive
    try:
        selected = normalize_check_names(checks or (), all_checks=all_checks)
    except ValueError as exc:
        raise typer.BadParameter(str(exc), param_hint="--check") from exc
    if use_interactive and not selected:
        try:
            selected = _interactive_selection()
        except ValueError as exc:
            raise typer.BadParameter(str(exc), param_hint="--check") from exc
    elif not selected:
        selected = DEFAULT_CHECKS

    selected_set = set(selected)
    if use_interactive:
        if selected_set.intersection({"chassis", "failsafe", "gimbal"}) and not allow_motion:
            allow_motion = Confirm.ask(
                "确认机器人位于平整净空地面，允许完整低速运动诊断吗？",
                default=False,
            )
        if "infrared" in selected_set and not allow_infrared:
            allow_infrared = Confirm.ask("确认允许发射一次红外信号吗？", default=False)
        if "gel" in selected_set and not allow_gel:
            allow_gel = Confirm.ask(
                "确认弹仓为空、枪口方向安全，允许触发一次水弹发射吗？",
                default=False,
            )

    config = DiagnosisConfig(
        checks=selected,
        output_base=output_dir,
        robot_ip=robot_ip,
        appid=appid,
        timeout=timeout,
        discovery_timeout=discovery_timeout,
        allow_motion=allow_motion,
        allow_infrared=allow_infrared,
        allow_gel=allow_gel,
        adb=adb,
        debug=debug,
    )
    if {"adb", "system"}.intersection(selected_set):
        console.print("[yellow]提示：诊断会临时开放 root ADB，并在结束时重启机器人关闭它。[/]")
    try:
        status, _, _ = run_diagnosis(config, console=console)
    except ValueError as exc:
        raise typer.BadParameter(str(exc)) from exc
    if status:
        raise typer.Exit(status)


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(argv) if argv is not None else None
    try:
        result = app(args=arguments, prog_name="hanppie", standalone_mode=False)
    except click.exceptions.Exit as exc:
        return int(exc.exit_code)
    except click.ClickException as exc:
        exc.show()
        return int(exc.exit_code)
    except KeyboardInterrupt:
        typer.echo("interrupted", err=True)
        return 130
    return int(result or 0)
