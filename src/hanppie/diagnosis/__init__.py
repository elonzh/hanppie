"""One complete, safety-gated diagnosis workflow for RoboMaster S1."""

from hanppie.diagnosis.model import (
    CHECKS,
    DEFAULT_CHECKS,
    DiagnosisConfig,
    DiagnosisResult,
    normalize_check_names,
    validate_safety,
)
from hanppie.diagnosis.runner import render_check_catalog, run_diagnosis

__all__ = [
    "CHECKS",
    "DEFAULT_CHECKS",
    "DiagnosisConfig",
    "DiagnosisResult",
    "normalize_check_names",
    "render_check_catalog",
    "run_diagnosis",
    "validate_safety",
]
