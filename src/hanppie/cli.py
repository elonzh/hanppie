"""Typer/Rich command-line entry point for Hanppie."""

from __future__ import annotations

import json
import os
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
agent_app = typer.Typer(
    help="运行带唤醒词、连续对话和视觉观察的 S1 语音智能体。",
    no_args_is_help=True,
    rich_markup_mode="rich",
)
app.add_typer(agent_app, name="agent")


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


@agent_app.command("login")
def agent_login_command(
    timeout: Annotated[
        float,
        typer.Option(min=30.0, help="等待浏览器设备授权完成的最长时间（秒）"),
    ] = 900.0,
) -> None:
    """不依赖 Codex CLI，直接完成 ChatGPT Codex OAuth 设备授权。"""

    from hanppie.agent.codex_auth import CodexOAuthManager

    oauth = CodexOAuthManager()
    try:
        auth_path = oauth.login(
            timeout_seconds=timeout,
            notify=lambda message: console.print(message, markup=False),
        )
    except Exception as exc:
        console.print(f"Codex 授权失败：{exc}", style="red", markup=False)
        raise typer.Exit(1) from exc
    finally:
        oauth.close()
    console.print(f"Codex 授权已保存：{auth_path}", markup=False)


@agent_app.command("logout")
def agent_logout_command() -> None:
    """删除 Hanppie 保存的 Codex OAuth 凭据。"""

    from hanppie.agent.codex_auth import CodexOAuthManager

    oauth = CodexOAuthManager()
    removed = oauth.logout()
    if removed:
        console.print(f"已删除 Codex 授权：{oauth.auth_path}", markup=False)
    else:
        console.print("当前没有 Hanppie Codex 授权。", markup=False)


@agent_app.command("run")
def agent_run_command(
    prompt: Annotated[
        list[str] | None,
        typer.Option(
            "--prompt", "-p", help="直接执行文本；可重复以连续对话，完成后退出，不启用音频"
        ),
    ] = None,
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
        typer.Option(min=0.1, help="单次机器人 Python 默认超时（秒）"),
    ] = 20.0,
    max_execution_timeout: Annotated[
        float,
        typer.Option(min=0.1, help="单次机器人 Python 最大超时（秒）"),
    ] = 60.0,
    wake_phrases: Annotated[
        list[str] | None,
        typer.Option("--wake-phrase", help="唤醒词；可重复，默认小憨批/小憨皮"),
    ] = None,
    active_timeout: Annotated[
        float,
        typer.Option(min=1.0, help="唤醒后免唤醒词连续对话时长（秒）"),
    ] = 45.0,
    auth: Annotated[
        str,
        typer.Option(help="模型授权：auto、codex 或 api-key"),
    ] = "auto",
    model: Annotated[str, typer.Option(help="OpenAI API Key 模式的规划模型")] = "gpt-5.6",
    vision_model: Annotated[
        str | None,
        typer.Option(help="OpenAI API Key 模式的视觉模型；默认与规划模型相同"),
    ] = None,
    codex_model: Annotated[
        str,
        typer.Option(help="Codex OAuth 模式的规划与视觉模型"),
    ] = "gpt-5.6-sol",
    codex_vision_model: Annotated[
        str | None, typer.Option(help="Codex 视觉模型；默认与规划模型相同")
    ] = None,
    reasoning_effort: Annotated[
        str, typer.Option(help="Codex 推理强度：none/minimal/low/medium/high/xhigh")
    ] = "low",
    model_timeout: Annotated[
        float, typer.Option(min=0.1, help="Codex 单次网络读写超时（秒），不是整轮截止时间")
    ] = 30.0,
    transcription_model: Annotated[
        str,
        typer.Option(help="OpenAI API Key 模式的语音转写模型"),
    ] = "gpt-transcribe",
    local_transcription_model: Annotated[
        str,
        typer.Option(help="Codex 模式的本地 faster-whisper 模型或目录"),
    ] = "small",
    tts_model: Annotated[
        str,
        typer.Option(help="OpenAI API Key 模式的语音合成模型"),
    ] = "gpt-4o-mini-tts",
    voice: Annotated[str, typer.Option(help="OpenAI API Key 模式的合成音色")] = "coral",
    tts: Annotated[
        bool,
        typer.Option("--tts/--no-tts", help="播放语音回复"),
    ] = True,
    audio_device: Annotated[
        str | None,
        typer.Option(help="sounddevice 输入/输出设备名称或编号"),
    ] = None,
    vad_threshold: Annotated[
        float,
        typer.Option(min=1.0, help="本地能量 VAD 的 RMS 阈值"),
    ] = 500.0,
    silence_ms: Annotated[
        int,
        typer.Option(min=100, help="判定一句话结束的静音时长（毫秒）"),
    ] = 480,
    max_utterance_seconds: Annotated[
        float,
        typer.Option(min=1.0, help="单段语音最长时长（秒）"),
    ] = 15.0,
    artifact_dir: Annotated[
        Path,
        typer.Option(help="智能体对话、调用和制品的根目录"),
    ] = Path(".hanppie/agent"),
    debug: Annotated[bool, typer.Option(help="捕获 App 协议调试输出")] = False,
) -> None:
    """通过 LangGraph 控制 S1：持续语音对话，或直接执行 --prompt。"""

    from hanppie.agent.audio import VoiceActivityConfig
    from hanppie.agent.model import AgentConfig
    from hanppie.agent.service import build_voice_agent

    if prompt is not None and any(not item.strip() for item in prompt):
        raise typer.BadParameter("prompt 不能为空", param_hint="--prompt")
    resolved_device: str | int | None = audio_device
    if audio_device is not None and audio_device.isdecimal():
        resolved_device = int(audio_device)
    try:
        executor = ExecutorConfig(
            robot_ip=robot_ip,
            appid=appid,
            local_ip=local_ip,
            discovery_timeout=discovery_timeout,
            connection_timeout=connection_timeout,
            execution_timeout=execution_timeout,
            max_execution_timeout=max_execution_timeout,
            artifact_base=artifact_dir / "runtime",
            debug=debug,
        )
        config = AgentConfig(
            executor=executor,
            wake_phrases=tuple(wake_phrases) if wake_phrases else ("小憨批", "小憨皮"),
            active_timeout_seconds=active_timeout,
            auth_provider=auth,
            model=model,
            vision_model=vision_model,
            codex_model=codex_model,
            codex_vision_model=codex_vision_model,
            reasoning_effort=reasoning_effort,
            model_timeout_seconds=model_timeout,
            transcription_model=transcription_model,
            local_transcription_model=local_transcription_model,
            tts_model=tts_model,
            voice=voice,
            artifact_base=artifact_dir,
            tts_enabled=tts,
        )
        audio_config = VoiceActivityConfig(
            rms_threshold=vad_threshold,
            silence_ms=silence_ms,
            max_utterance_seconds=max_utterance_seconds,
        )
        service = build_voice_agent(
            config,
            text_only=prompt is not None,
            audio_config=audio_config,
            audio_device=resolved_device,
            emit=lambda message: console.print(message, markup=False),
        )
    except Exception as exc:
        console.print(f"无法启动智能体：{exc}", style="red", markup=False)
        raise typer.Exit(1) from exc

    if prompt is not None:
        failed = False
        try:
            for item in prompt:
                reply = service.process_prompt(item)
                if reply.failed or any(
                    result.output.get("ok") is False for result in reply.tool_results
                ):
                    failed = True
                    break
        except KeyboardInterrupt as exc:
            raise typer.Exit(130) from exc
        except Exception as exc:
            console.print(f"执行失败：{exc}", style="red", markup=False)
            raise typer.Exit(1) from exc
        finally:
            service.close()
        if failed:
            raise typer.Exit(1)
        return

    if tts:
        if config.auth_provider == "codex" or (
            config.auth_provider == "auto" and not os.environ.get("OPENAI_API_KEY")
        ):
            console.print("提示：Codex 模式使用本机系统语音播报。", style="dim", markup=False)
        else:
            console.print("提示：回复声音由 AI 合成。", style="dim", markup=False)
    service.run_forever()


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
