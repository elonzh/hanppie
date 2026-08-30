"""Typer/Rich command-line entry point for Hanppie."""

from __future__ import annotations

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

app = typer.Typer(
    name="hanppie",
    help="保存、研究和编程控制 DJI RoboMaster S1。",
    no_args_is_help=True,
    rich_markup_mode="rich",
    pretty_exceptions_enable=False,
)
console = Console()


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
