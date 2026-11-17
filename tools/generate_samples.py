#!/usr/bin/env python3
"""Create short local sample tracks for a manual play-through.

Embedded lyrics are written into the file. Sidecar lyrics are a same-name .lrc.
Nothing here is fetched at runtime by the app.
"""

import math
import struct
import subprocess
import wave
from pathlib import Path


def tone(path: Path, seconds: float, amp: float, bursts: bool, freq: float = 196.0) -> None:
    rate = 44100
    frames = int(rate * seconds)
    with wave.open(str(path), "w") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        data = bytearray()
        for i in range(frames):
            t = i / rate
            gate = 1.0
            if bursts:
                gate = 1.0 if (t % 0.25) < 0.04 else 0.0
            sample = int(max(-32767, min(32767, math.sin(2 * math.pi * freq * t) * amp * gate * 32767)))
            data += struct.pack("<h", sample)
        handle.writeframes(data)


def id3_sylt(lines: list[tuple[int, str]]) -> bytes:
    body = bytearray([3, ord("z"), ord("h"), ord("o"), 2, 1, 0])
    for time_ms, text in lines:
        body += text.encode("utf-8") + b"\x00"
        body += struct.pack(">I", time_ms)
    frame = b"SYLT" + struct.pack(">I", len(body)) + b"\x00\x00" + body
    size = len(frame)
    header = bytearray(b"ID3\x03\x00\x00")
    header += bytes(
        [
            (size >> 21) & 0x7F,
            (size >> 14) & 0x7F,
            (size >> 7) & 0x7F,
            size & 0x7F,
        ]
    )
    return bytes(header) + frame


def main() -> None:
    root = Path(__file__).resolve().parents[1] / "sample-media"
    root.mkdir(exist_ok=True)
    # Three calmer tones and three louder burst tracks, plus lyric cases.
    tone(root / "quiet_river.wav", 8, 0.03, False, 174)
    tone(root / "quiet_lamp.wav", 8, 0.025, False, 220)
    tone(root / "quiet_room.wav", 8, 0.04, False, 146)
    tone(root / "loud_steps.wav", 8, 0.9, True, 90)
    tone(root / "loud_rain.wav", 8, 0.85, True, 140)
    tone(root / "loud_market.wav", 8, 0.95, True, 110)
    (root / "quiet_river.lrc").write_text(
        "[00:01.00]河还没醒\n[00:03.00]灯在对面\n[00:05.50]这一句来自同名歌词文件\n",
        encoding="utf-8",
    )
    plain = root / "letter.wav"
    tone(plain, 8, 0.2, False, 262)
    mp3 = root / "letter.mp3"
    subprocess.check_call(
        ["ffmpeg", "-y", "-i", str(plain), "-codec:a", "libmp3lame", "-q:a", "5", str(mp3)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    tagged = id3_sylt([(800, "信是写在文件里的"), (2500, "不是网上下来的"), (5000, "第二行该亮起来")]) + mp3.read_bytes()
    mp3.write_bytes(tagged)
    plain.unlink()
    flac = root / "night.flac"
    tone(root / "_night.wav", 6, 0.15, False, 330)
    subprocess.check_call(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(root / "_night.wav"),
            "-c:a",
            "flac",
            str(flac),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    (root / "_night.wav").unlink()
    subprocess.check_call(
        ["ffmpeg", "-y", "-i", str(root / "quiet_lamp.wav"), "-c:a", "libvorbis", str(root / "lamp.ogg")],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    subprocess.check_call(
        ["ffmpeg", "-y", "-i", str(root / "quiet_room.wav"), "-c:a", "aac", "-b:a", "128k", str(root / "room.m4a")],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    print(f"wrote samples in {root}")


if __name__ == "__main__":
    main()
