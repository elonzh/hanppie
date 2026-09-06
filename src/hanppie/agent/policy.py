"""Boundaries for Python produced by the conversational model."""

from __future__ import annotations

import ast


class GeneratedCodeRejected(ValueError):
    """Generated Python crossed the agent's constrained execution boundary."""


class RobotCodePolicy:
    """Allow composition around the injected robot facade, not arbitrary host access."""

    _blocked_calls = {
        "breakpoint",
        "compile",
        "delattr",
        "eval",
        "exec",
        "getattr",
        "globals",
        "input",
        "locals",
        "open",
        "setattr",
        "vars",
        "__import__",
    }
    _allowed_globals = {
        "Exception",
        "RuntimeError",
        "abs",
        "bool",
        "checkpoint",
        "dict",
        "enumerate",
        "float",
        "int",
        "len",
        "list",
        "max",
        "min",
        "print",
        "range",
        "result",
        "robot",
        "round",
        "set",
        "sleep",
        "str",
        "sum",
        "time",
        "tuple",
        "zip",
    }

    def validate(self, code: str) -> None:
        if not code.strip():
            raise GeneratedCodeRejected("generated code is empty")
        try:
            tree = ast.parse(code, mode="exec")
        except SyntaxError as exc:
            raise GeneratedCodeRejected(f"generated code is invalid Python: {exc.msg}") from exc

        saw_robot = False
        local_names = {
            node.id
            for node in ast.walk(tree)
            if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store)
        }
        for node in ast.walk(tree):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                raise GeneratedCodeRejected("imports are not available to generated robot code")
            if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
                raise GeneratedCodeRejected("definitions are not available to generated robot code")
            if isinstance(node, ast.Name):
                if node.id == "robot":
                    saw_robot = True
                if node.id.startswith("__"):
                    raise GeneratedCodeRejected("dunder names are not available")
                if (
                    isinstance(node.ctx, ast.Load)
                    and node.id not in self._allowed_globals
                    and node.id not in local_names
                ):
                    raise GeneratedCodeRejected(f"global name {node.id!r} is not available")
            if isinstance(node, ast.Attribute) and node.attr.startswith("_"):
                raise GeneratedCodeRejected("private attributes are not available")
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                if node.func.id in self._blocked_calls:
                    raise GeneratedCodeRejected(f"{node.func.id}() is not available")

        if not saw_robot:
            raise GeneratedCodeRejected("generated code must operate through the robot facade")
