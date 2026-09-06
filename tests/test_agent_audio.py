from __future__ import annotations

import io
import os
import wave
from types import SimpleNamespace

import numpy as np
import pytest

from hanppie.agent.audio import (
    FasterWhisperTranscriber,
    MicrophoneListener,
    OpenAITranscriber,
    OpenAITTSSpeaker,
    SystemSpeaker,
    UtteranceSegmenter,
    VoiceActivityConfig,
    pcm_to_wav,
)


def _block(value: int, samples: int) -> bytes:
    return np.full(samples, value, dtype="<i2").tobytes()


def test_segmenter_and_wav_conversion() -> None:
    config = VoiceActivityConfig(
        sample_rate=1_000,
        block_ms=100,
        rms_threshold=100,
        silence_ms=200,
        pre_roll_ms=100,
        max_utterance_seconds=2,
    )
    segmenter = UtteranceSegmenter(config)
    silence = _block(0, 100)
    speech = _block(1_000, 100)

    assert segmenter.feed(silence) is None
    assert segmenter.feed(speech) is None
    assert segmenter.feed(speech) is None
    assert segmenter.feed(silence) is None
    pcm = segmenter.feed(silence)

    assert pcm is not None
    assert len(pcm) == 5 * len(silence)
    wav_data = pcm_to_wav(pcm, config)
    with wave.open(io.BytesIO(wav_data), "rb") as reader:
        assert reader.getframerate() == 1_000
        assert reader.getnchannels() == 1
        assert reader.readframes(reader.getnframes()) == pcm


def test_segmenter_validates_blocks_and_config() -> None:
    with pytest.raises(ValueError):
        VoiceActivityConfig(channels=2)
    segmenter = UtteranceSegmenter(VoiceActivityConfig())
    with pytest.raises(ValueError, match="expected"):
        segmenter.feed(b"short")


def test_microphone_listener_returns_first_utterance() -> None:
    config = VoiceActivityConfig(
        sample_rate=1_000,
        block_ms=100,
        rms_threshold=100,
        silence_ms=100,
        pre_roll_ms=0,
    )

    class Stream:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

        def __enter__(self):
            callback = self.kwargs["callback"]
            callback(_block(1_000, 100), 100, None, None)
            callback(_block(0, 100), 100, None, None)
            return self

        def __exit__(self, *_args):
            return None

    fake_sounddevice = SimpleNamespace(RawInputStream=Stream)
    listener = MicrophoneListener(config, device=3, sounddevice_module=fake_sounddevice)

    wav_data = listener.listen()
    assert listener.last_speech_end is not None

    with wave.open(io.BytesIO(wav_data), "rb") as reader:
        assert reader.getnframes() == 200


def test_openai_transcriber_and_tts_adapters() -> None:
    captured = {}

    class Transcriptions:
        def create(self, **kwargs):
            captured["transcription"] = kwargs
            return SimpleNamespace(text="  小憨批，前进  ")

    class Speech:
        def create(self, **kwargs):
            captured["speech"] = kwargs
            return SimpleNamespace(read=lambda: _block(123, 10))

    class SoundDevice:
        def play(self, samples, **kwargs):
            captured["samples"] = samples
            captured["play"] = kwargs

        def wait(self):
            captured["waited"] = True

    client = SimpleNamespace(
        audio=SimpleNamespace(transcriptions=Transcriptions(), speech=Speech())
    )
    transcriber = OpenAITranscriber(client, model="gpt-transcribe", language="zh")
    speaker = OpenAITTSSpeaker(
        client,
        model="gpt-4o-mini-tts",
        voice="coral",
        device=2,
        sounddevice_module=SoundDevice(),
    )

    assert transcriber.transcribe(b"wav") == "小憨批，前进"
    assert captured["transcription"]["file"].name == "utterance.wav"
    speaker.speak("收到")
    speaker.speak(" ")

    assert captured["speech"]["response_format"] == "pcm"
    assert captured["play"] == {"samplerate": 24_000, "device": 2}
    assert captured["waited"] is True


def test_local_transcriber_consumes_lazy_segments_and_removes_wav() -> None:
    captured = {}

    class WhisperModel:
        def __init__(self, name, **kwargs):
            captured["init"] = (name, kwargs)

        def transcribe(self, path, **kwargs):
            captured["path"] = path
            captured["exists_during_call"] = os.path.exists(path)
            captured["arguments"] = kwargs
            return iter([SimpleNamespace(text=" 小憨批"), SimpleNamespace(text="，前进 ")]), None

    transcriber = FasterWhisperTranscriber(
        model="small",
        language="zh",
        whisper_model_class=WhisperModel,
    )

    assert transcriber.transcribe(b"wav") == "小憨批，前进"
    assert captured["init"] == ("small", {"device": "cpu", "compute_type": "int8"})
    assert captured["exists_during_call"] is True
    assert captured["arguments"]["language"] == "zh"
    assert captured["arguments"]["beam_size"] == 1
    assert not os.path.exists(captured["path"])


def test_system_speaker_uses_platform_command_without_shell() -> None:
    calls = []

    def run(*args, **kwargs):
        calls.append((args, kwargs))

    speaker = SystemSpeaker(system="Darwin", which=lambda command: f"/usr/bin/{command}", run=run)
    speaker.speak("收到")
    speaker.speak(" ")

    assert calls == [((["/usr/bin/say", "收到"],), {"check": True})]
