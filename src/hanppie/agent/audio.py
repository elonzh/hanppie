"""Microphone segmentation plus OpenAI speech input/output adapters."""

from __future__ import annotations

import io
import os
import platform
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import wave
from collections import deque
from dataclasses import dataclass
from typing import Any

import numpy as np


@dataclass(frozen=True)
class VoiceActivityConfig:
    sample_rate: int = 16_000
    channels: int = 1
    block_ms: int = 30
    rms_threshold: float = 500.0
    silence_ms: int = 480
    pre_roll_ms: int = 240
    max_utterance_seconds: float = 15.0

    def __post_init__(self) -> None:
        if self.sample_rate <= 0 or self.channels != 1 or self.block_ms <= 0:
            raise ValueError("voice activity detection requires positive mono audio settings")
        if self.rms_threshold <= 0 or self.silence_ms <= 0 or self.pre_roll_ms < 0:
            raise ValueError("voice activity thresholds must be valid")
        if self.max_utterance_seconds <= 0:
            raise ValueError("max_utterance_seconds must be greater than 0")


class UtteranceSegmenter:
    """Turn 16-bit mono PCM blocks into bounded utterances using energy VAD."""

    def __init__(self, config: VoiceActivityConfig) -> None:
        self.config = config
        self._pre_roll_blocks = max(0, config.pre_roll_ms // config.block_ms)
        self._silence_blocks = max(1, config.silence_ms // config.block_ms)
        self._max_blocks = max(1, int(config.max_utterance_seconds * 1000 / config.block_ms))
        self._pre_roll: deque[bytes] = deque(maxlen=self._pre_roll_blocks + 1)
        self._active: list[bytes] | None = None
        self._trailing_silence = 0

    def feed(self, block: bytes) -> bytes | None:
        expected_samples = int(self.config.sample_rate * self.config.block_ms / 1000)
        expected_bytes = expected_samples * self.config.channels * 2
        if len(block) != expected_bytes:
            raise ValueError(f"expected {expected_bytes} PCM bytes, got {len(block)}")

        rms = self._rms(block)
        if self._active is None:
            self._pre_roll.append(block)
            if rms < self.config.rms_threshold:
                return None
            self._active = list(self._pre_roll)
            self._pre_roll.clear()
            self._trailing_silence = 0
            if len(self._active) >= self._max_blocks:
                utterance = b"".join(self._active)
                self.reset()
                return utterance
            return None

        self._active.append(block)
        if rms < self.config.rms_threshold:
            self._trailing_silence += 1
        else:
            self._trailing_silence = 0

        if len(self._active) >= self._max_blocks or self._trailing_silence >= self._silence_blocks:
            utterance = b"".join(self._active)
            self.reset()
            return utterance
        return None

    def reset(self) -> None:
        self._pre_roll.clear()
        self._active = None
        self._trailing_silence = 0

    @staticmethod
    def _rms(block: bytes) -> float:
        samples = np.frombuffer(block, dtype="<i2").astype(np.float64)
        return float(np.sqrt(np.mean(np.square(samples)))) if samples.size else 0.0


def pcm_to_wav(pcm: bytes, config: VoiceActivityConfig) -> bytes:
    stream = io.BytesIO()
    with wave.open(stream, "wb") as writer:
        writer.setnchannels(config.channels)
        writer.setsampwidth(2)
        writer.setframerate(config.sample_rate)
        writer.writeframes(pcm)
    return stream.getvalue()


class MicrophoneListener:
    """Wait for one local VAD-delimited utterance."""

    def __init__(
        self,
        config: VoiceActivityConfig,
        *,
        device: str | int | None = None,
        sounddevice_module: Any | None = None,
    ) -> None:
        if sounddevice_module is None:
            try:
                import sounddevice as sounddevice_module
            except ImportError as exc:  # pragma: no cover - dependency failure path
                raise RuntimeError("sounddevice is required for microphone input") from exc
        self.config = config
        self.device = device
        self._sounddevice = sounddevice_module
        self.last_speech_end: float | None = None

    def listen(self) -> bytes:
        blocks: queue.Queue[tuple[bytes, float]] = queue.Queue(maxsize=256)
        self.last_speech_end = None
        overflowed = threading.Event()
        segmenter = UtteranceSegmenter(self.config)
        blocksize = int(self.config.sample_rate * self.config.block_ms / 1000)

        def callback(indata: bytes, frames: int, _time: Any, status: Any) -> None:
            if frames != blocksize:
                return
            try:
                blocks.put_nowait((bytes(indata), time.perf_counter()))
            except queue.Full:
                overflowed.set()

        with self._sounddevice.RawInputStream(
            samplerate=self.config.sample_rate,
            blocksize=blocksize,
            device=self.device,
            channels=self.config.channels,
            dtype="int16",
            callback=callback,
        ):
            while True:
                block, captured_at = blocks.get()
                if overflowed.is_set():
                    segmenter.reset()
                    overflowed.clear()
                    self.last_speech_end = None
                if segmenter._rms(block) >= self.config.rms_threshold:
                    self.last_speech_end = captured_at
                utterance = segmenter.feed(block)
                if utterance is not None:
                    return pcm_to_wav(utterance, self.config)


class OpenAITranscriber:
    def __init__(self, client: Any, *, model: str, language: str | None = "zh") -> None:
        self._client = client
        self.model = model
        self.language = language

    def transcribe(self, wav_data: bytes) -> str:
        audio_file = io.BytesIO(wav_data)
        audio_file.name = "utterance.wav"
        arguments: dict[str, Any] = {"model": self.model, "file": audio_file}
        if self.language:
            arguments["language"] = self.language
        response = self._client.audio.transcriptions.create(**arguments)
        text = getattr(response, "text", "")
        return str(text).strip()


class FasterWhisperTranscriber:
    """Run multilingual speech recognition locally without an API credential."""

    def __init__(
        self,
        *,
        model: str = "small",
        language: str | None = "zh",
        whisper_model_class: Any | None = None,
    ) -> None:
        if whisper_model_class is None:
            try:
                from faster_whisper import WhisperModel as whisper_model_class
            except ImportError as exc:  # pragma: no cover - dependency failure path
                raise RuntimeError("Codex 授权模式需要 faster-whisper 进行本地语音转写") from exc
        self.model_name = model
        self.language = language
        self._model = whisper_model_class(model, device="cpu", compute_type="int8")

    def transcribe(self, wav_data: bytes) -> str:
        path = ""
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as audio_file:
                audio_file.write(wav_data)
                path = audio_file.name
            segments, _info = self._model.transcribe(
                path,
                language=self.language,
                beam_size=1,
                vad_filter=False,
                condition_on_previous_text=False,
                initial_prompt="小憨批，小憨皮，RoboMaster S1，前进，后退，转圈，云台，拍照。",
            )
            return "".join(str(segment.text) for segment in segments).strip()
        finally:
            if path:
                try:
                    os.unlink(path)
                except FileNotFoundError:
                    pass


class SystemSpeaker:
    """Use the operating system's speech synthesizer for credential-free replies."""

    def __init__(
        self,
        *,
        system: str | None = None,
        which: Any = shutil.which,
        run: Any = subprocess.run,
    ) -> None:
        self._run = run
        current = system or platform.system()
        if current == "Darwin" and which("say"):
            self._kind = "say"
            self._command = [str(which("say"))]
        elif current == "Linux" and which("spd-say"):
            self._kind = "argv"
            self._command = [str(which("spd-say")), "--wait"]
        elif current == "Linux" and which("espeak"):
            self._kind = "argv"
            self._command = [str(which("espeak"))]
        elif current == "Windows" and which("powershell"):
            self._kind = "stdin"
            self._command = [
                str(which("powershell")),
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                (
                    "Add-Type -AssemblyName System.Speech; "
                    "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                    "$s.Speak([Console]::In.ReadToEnd())"
                ),
            ]
        else:
            raise RuntimeError("系统没有可用的语音合成命令；请安装 spd-say/espeak，或使用 --no-tts")

    def speak(self, text: str) -> None:
        if not text.strip():
            return
        if self._kind == "stdin":
            self._run(self._command, input=text, text=True, check=True)
        else:
            self._run([*self._command, text], check=True)


class OpenAITTSSpeaker:
    """Synthesize AI speech as 24 kHz signed 16-bit PCM on the host speaker."""

    def __init__(
        self,
        client: Any,
        *,
        model: str,
        voice: str,
        device: str | int | None = None,
        sounddevice_module: Any | None = None,
    ) -> None:
        if sounddevice_module is None:
            try:
                import sounddevice as sounddevice_module
            except ImportError as exc:  # pragma: no cover - dependency failure path
                raise RuntimeError("sounddevice is required for speech playback") from exc
        self._client = client
        self._sounddevice = sounddevice_module
        self.model = model
        self.voice = voice
        self.device = device

    def speak(self, text: str) -> None:
        if not text.strip():
            return
        response = self._client.audio.speech.create(
            model=self.model,
            voice=self.voice,
            input=text,
            response_format="pcm",
        )
        payload = response.read() if hasattr(response, "read") else response.content
        samples = np.frombuffer(payload, dtype="<i2")
        self._sounddevice.play(samples, samplerate=24_000, device=self.device)
        self._sounddevice.wait()
