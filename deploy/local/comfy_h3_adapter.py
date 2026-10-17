#!/usr/bin/env python3
"""MiniMax H3 V2-compatible facade backed by a remote ComfyUI instance."""

from __future__ import annotations

import hashlib
import io
import json
import math
import mimetypes
import os
import random
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys

from PIL import Image

sys.path.insert(0, str(Path(__file__).with_name("integration")))
from gpu_scheduler_client import GpuSchedulerClient, SchedulerApiError  # noqa: E402


ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / ".runtime"
WORKFLOW_FILE = Path(os.getenv("H3_WORKFLOW_FILE", str(Path(__file__).with_name("h3_i2v_api.json"))))
TASKS_FILE = RUNTIME / "comfy-h3-tasks.json"
COMFY_URL = os.getenv("COMFY_URL", "http://10.190.0.222:8188").rstrip("/")
ACCEL_COMFY_URL = os.getenv("H3_ACCEL_COMFY_URL", COMFY_URL).rstrip("/")
PUBLIC_BASE = os.getenv("AID_PUBLIC_BASE", "http://127.0.0.1:18080").rstrip("/")
LISTEN_HOST = os.getenv("H3_ADAPTER_HOST", "127.0.0.1")
LISTEN_PORT = int(os.getenv("H3_ADAPTER_PORT", "19090"))
FFPROBE_BIN = os.getenv("FFPROBE_BIN", "/opt/homebrew/bin/ffprobe")
FFMPEG_BIN = os.getenv("FFMPEG_BIN", "/opt/homebrew/bin/ffmpeg")
MAX_IMAGE_DOWNLOAD = 32 * 1024 * 1024
MAX_VIDEO_DOWNLOAD = 50 * 1024 * 1024
MAX_AUDIO_DOWNLOAD = 15 * 1024 * 1024
MAX_REFERENCE_IMAGES = 3
MAX_REFERENCE_VIDEOS = 1
MAX_REFERENCE_AUDIOS = 1
TASK_LOCK = threading.Lock()
MAX_SAFE_DURATION = int(os.getenv("H3_MAX_SAFE_DURATION", "10"))
LONG_SHOT_POLICY = os.getenv("H3_LONG_SHOT_POLICY", "cap").strip().lower()
FACE_SWAP_ENABLED = os.getenv("H3_FACE_SWAP_ENABLED", "true").strip().lower() in {"1", "true", "yes", "on"}
TURBO_ENABLED = os.getenv("H3_TURBO_ENABLED", "true").strip().lower() in {"1", "true", "yes", "on"}
FAST_VSA_ENABLED = os.getenv("H3_FAST_VSA_ENABLED", "false").strip().lower() in {"1", "true", "yes", "on"}
FAST_VSA_SCHEDULER_ENABLED = os.getenv(
    "H3_FAST_VSA_SCHEDULER_ENABLED", "false"
).strip().lower() in {"1", "true", "yes", "on"}
GPU_SCHEDULER_REQUIRED = os.getenv(
    "H3_GPU_SCHEDULER_REQUIRED", "false"
).strip().lower() in {"1", "true", "yes", "on"}
FAST_VSA_SCHEDULER_PROFILE = os.getenv(
    "H3_FAST_VSA_SCHEDULER_PROFILE", "h3-single-fast"
).strip()
QUALITY_SCHEDULER_ENABLED = os.getenv(
    "H3_QUALITY_SCHEDULER_ENABLED", "false"
).strip().lower() in {"1", "true", "yes", "on"}
QUALITY_SCHEDULER_PROFILE = os.getenv(
    "H3_QUALITY_SCHEDULER_PROFILE", "h3-standard-quality"
).strip()
VEDA_SCHEDULER_ENABLED = os.getenv(
    "H3_VEDA_SCHEDULER_ENABLED", "false"
).strip().lower() in {"1", "true", "yes", "on"}
VEDA_SCHEDULER_PROFILE = os.getenv(
    "H3_VEDA_SCHEDULER_PROFILE", "h3-veda-fast"
).strip()
VEDA_ENABLED = os.getenv("H3_VEDA_ENABLED", "false").strip().lower() in {"1", "true", "yes", "on"}
VEDA_COMFY_URL = os.getenv("H3_VEDA_COMFY_URL", "http://10.190.0.222:8192").rstrip("/")
RESOURCE_RELEASE_ENABLED = os.getenv("H3_RESOURCE_RELEASE_ENABLED", "true").strip().lower() in {"1", "true", "yes", "on"}
RESOURCE_RELEASE_URLS = tuple(
    item.strip() for item in os.getenv(
        "H3_RESOURCE_RELEASE_URLS",
        "http://10.190.0.222:7862/api/gpu/release,http://10.190.0.222:7873/api/gpu/release",
    ).split(",") if item.strip()
)
RESOURCE_RELEASE_MIN_INTERVAL = max(0, int(os.getenv("H3_RESOURCE_RELEASE_MIN_INTERVAL", "30")))
KEEP_WARM_SECONDS = max(0, int(os.getenv("H3_KEEP_WARM_SECONDS", "600")))
FAST_VSA_KEEP_WARM_SECONDS = max(
    KEEP_WARM_SECONDS,
    int(os.getenv("H3_FAST_VSA_KEEP_WARM_SECONDS", "21600")),
)
FAST_VSA_MIN_TOKENS = max(1, int(os.getenv("H3_FAST_VSA_MIN_TOKENS", "8192")))
TASK_LOST_TIMEOUT_SECONDS = max(7200, int(os.getenv("H3_TASK_LOST_TIMEOUT_SECONDS", "10800")))
RUNTIME_RELEASE_LOCK = threading.RLock()
RUNTIME_RELEASE_TIMERS: dict[str, threading.Timer] = {}
PEER_RELEASE_LOCK = threading.Lock()
PEER_RELEASE_LAST_AT = 0.0
TURBO_FL2V_LORA = os.getenv(
    "H3_TURBO_FL2V_LORA",
    "minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors",
)
TURBO_REF2V_LORA = os.getenv(
    "H3_TURBO_REF2V_LORA",
    "minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors",
)
FAST_VSA_MODEL = os.getenv(
    "H3_FAST_VSA_MODEL",
    "fastvideo_fasth3_8step_v2_pruned_int8_convrot.safetensors",
)
VEDA_PREDICTOR = os.getenv(
    "H3_VEDA_PREDICTOR",
    "minimax_h3_t2va_veda_8nfe_600step_preview_fp8.safetensors",
)

RESOLUTION_SIZES = {
    "480P": {
        "21:9": (960, 416),
        "16:9": (832, 480),
        "4:3": (640, 480),
        "1:1": (480, 480),
        "3:4": (480, 640),
        "9:16": (480, 832),
    },
    "768P": {
        "21:9": (1344, 576),
        "16:9": (1344, 768),
        "4:3": (1152, 864),
        "1:1": (992, 992),
        "3:4": (864, 1152),
        "9:16": (768, 1344),
    },
}
SUPPORTED_RESOLUTIONS = tuple(RESOLUTION_SIZES)


class AdapterError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def http_json(url: str, *, method: str = "GET", body: dict | None = None, timeout: int = 30) -> dict:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:1000]
        raise AdapterError(502, f"ComfyUI HTTP {exc.code}: {detail}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise AdapterError(503, f"ComfyUI unavailable: {exc}") from exc


def release_comfy_models(comfy_url: str) -> None:
    """Best-effort cache release when switching between the blue/green H3 runtimes."""
    data = json.dumps({"unload_models": True, "free_memory": True}).encode()
    req = urllib.request.Request(comfy_url.rstrip("/") + "/free", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20):
            pass
    except Exception as exc:
        print(f"Unable to release ComfyUI model cache at {comfy_url}: {exc}", flush=True)


def release_peer_gpu_models() -> None:
    """Best-effort GPU handoff from idle TTS/ASR services to H3."""
    global PEER_RELEASE_LAST_AT
    if not RESOURCE_RELEASE_ENABLED or not RESOURCE_RELEASE_URLS:
        return
    with PEER_RELEASE_LOCK:
        now = time.monotonic()
        if now - PEER_RELEASE_LAST_AT < RESOURCE_RELEASE_MIN_INTERVAL:
            return
        PEER_RELEASE_LAST_AT = now
    for url in RESOURCE_RELEASE_URLS:
        req = urllib.request.Request(url, data=b"{}", method="POST")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                response.read()
        except urllib.error.HTTPError as exc:
            if exc.code == 409:
                print(f"GPU peer busy, skipping release: {url}", flush=True)
            else:
                print(f"GPU peer release HTTP {exc.code}: {url}", flush=True)
        except Exception as exc:
            print(f"Unable to release GPU peer at {url}: {exc}", flush=True)


def cancel_scheduled_comfy_release(comfy_url: str) -> None:
    with RUNTIME_RELEASE_LOCK:
        timer = RUNTIME_RELEASE_TIMERS.pop(comfy_url.rstrip("/"), None)
        if timer is not None:
            timer.cancel()


def _release_comfy_if_idle(comfy_url: str) -> None:
    normalized = comfy_url.rstrip("/")
    with RUNTIME_RELEASE_LOCK:
        RUNTIME_RELEASE_TIMERS.pop(normalized, None)
        try:
            queue = http_json(normalized + "/queue", timeout=15)
            busy = any(queue.get(key) for key in ("queue_running", "queue_pending"))
        except Exception as exc:
            print(f"Unable to inspect ComfyUI queue before idle release at {normalized}: {exc}", flush=True)
            busy = True
        if busy:
            schedule_comfy_release(normalized, delay_seconds=60)
            return
        release_comfy_models(normalized)


def schedule_comfy_release(comfy_url: str, *, delay_seconds: int | None = None) -> None:
    normalized = comfy_url.rstrip("/")
    delay = KEEP_WARM_SECONDS if delay_seconds is None else max(0, delay_seconds)
    with RUNTIME_RELEASE_LOCK:
        old = RUNTIME_RELEASE_TIMERS.pop(normalized, None)
        if old is not None:
            old.cancel()
        if delay == 0:
            release_comfy_models(normalized)
            return
        timer = threading.Timer(delay, _release_comfy_if_idle, args=(normalized,))
        timer.daemon = True
        RUNTIME_RELEASE_TIMERS[normalized] = timer
        timer.start()


def keep_warm_seconds_for(metadata: dict) -> int:
    """Keep the isolated FastH3 worker warm longer than shared production runtimes."""
    if metadata.get("acceleration") == "fast_vsa":
        return FAST_VSA_KEEP_WARM_SECONDS
    return KEEP_WARM_SECONDS


def download_media(
    url: str, *, max_bytes: int = MAX_IMAGE_DOWNLOAD, media_label: str = "Reference media"
) -> tuple[bytes, str, str]:
    if url.startswith("/"):
        url = PUBLIC_BASE + url
    req = urllib.request.Request(url, headers={"User-Agent": "AID-Studio-H3-Adapter/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            content_type = response.headers.get_content_type()
            data = response.read(max_bytes + 1)
    except Exception as exc:
        raise AdapterError(400, f"Unable to read {media_label.lower()}: {exc}") from exc
    if len(data) > max_bytes:
        raise AdapterError(400, f"{media_label} exceeds {max_bytes // (1024 * 1024)} MiB")
    suffix = Path(urllib.parse.urlparse(url).path).suffix.lower()
    if not suffix:
        suffix = mimetypes.guess_extension(content_type) or ".png"
    return data, suffix, content_type


def upload_media(data: bytes, suffix: str, content_type: str, *, prefix: str) -> str:
    boundary = "----aidstudio" + uuid.uuid4().hex
    filename = f"{prefix}-{uuid.uuid4().hex}{suffix}"
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n".encode(),
        data,
        f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\ninput\r\n--{boundary}--\r\n".encode(),
    ]
    req = urllib.request.Request(COMFY_URL + "/upload/image", data=b"".join(parts), method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            result = json.loads(response.read().decode())
    except Exception as exc:
        raise AdapterError(502, f"Unable to upload reference media to ComfyUI: {exc}") from exc
    name = result.get("name")
    if not name:
        raise AdapterError(502, "ComfyUI media upload returned no filename")
    subfolder = result.get("subfolder", "")
    return f"{subfolder}/{name}" if subfolder else name


def upload_image(data: bytes, suffix: str, content_type: str) -> tuple[str, tuple[int, int]]:
    try:
        with Image.open(io.BytesIO(data)) as image:
            source_size = image.size
            image.verify()
    except Exception as exc:
        raise AdapterError(400, "Reference media is not a valid image") from exc

    return upload_media(data, suffix, content_type, prefix="aid-h3"), source_size


def probe_media_duration(data: bytes, suffix: str) -> float:
    path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as handle:
            handle.write(data)
            path = handle.name
        result = subprocess.run(
            [FFPROBE_BIN, "-v", "error", "-show_entries", "format=duration", "-of", "default=nk=1:nw=1", path],
            check=True,
            capture_output=True,
            text=True,
            timeout=15,
        )
        return max(0.0, float(result.stdout.strip()))
    except (OSError, ValueError, subprocess.SubprocessError):
        return 0.0
    finally:
        if path:
            try:
                os.unlink(path)
            except OSError:
                pass


def prepare_reference_video(
    data: bytes, suffix: str, target_seconds: int
) -> tuple[bytes, str, str, float]:
    """Trim and downscale reference footage before remote H3 conditioning.

    Ref2VA keeps reference-frame conditioning in the sampler. Passing an entire
    long/high-resolution upload can therefore exhaust a 24 GiB card even when
    the requested output is short. Keep only the portion the output can use and
    normalize it to the local 480P profile.
    """
    input_path = None
    output_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as handle:
            handle.write(data)
            input_path = handle.name
        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as handle:
            output_path = handle.name
        subprocess.run(
            [
                FFMPEG_BIN,
                "-y",
                "-i",
                input_path,
                "-t",
                str(max(2, target_seconds)),
                "-map",
                "0:v:0",
                "-map",
                "0:a?",
                "-vf",
                "fps=24,scale=832:480:force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-c:a",
                "aac",
                "-b:a",
                "96k",
                "-movflags",
                "+faststart",
                output_path,
            ],
            check=True,
            capture_output=True,
            timeout=120,
        )
        prepared = Path(output_path).read_bytes()
        if not prepared:
            raise AdapterError(400, "Reference video preprocessing returned an empty file")
        return prepared, ".mp4", "video/mp4", probe_media_duration(prepared, ".mp4")
    except AdapterError:
        raise
    except (OSError, subprocess.SubprocessError) as exc:
        raise AdapterError(400, "Reference video preprocessing failed; please use an H.264 MP4 file") from exc
    finally:
        for path in (input_path, output_path):
            if path:
                try:
                    os.unlink(path)
                except OSError:
                    pass


def grid_frames(duration: int) -> int:
    # H3 supports 17k+5 frames. Choose the closest grid point at 24 fps.
    frames = round((duration * 24 - 5) / 17) * 17 + 5
    return min(362, max(5, frames))


def adaptive_size(source: tuple[int, int] | None, resolution: str) -> tuple[int, int]:
    ratio_sizes = RESOLUTION_SIZES[resolution]
    if not source or source[0] <= 0 or source[1] <= 0:
        return ratio_sizes["16:9"]
    ratio = source[0] / source[1]
    return min(ratio_sizes.values(), key=lambda size: abs(math.log((size[0] / size[1]) / ratio)))


def build_fast_vsa_workflow(prompt: str, width: int, height: int, duration: int) -> dict:
    """FastH3 V2 is an 8-step T2VA checkpoint trained for VSA at 10% keep."""
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": FAST_VSA_MODEL, "weight_dtype": "default"}},
        "2": {"class_type": "MiniMaxH3SigmaShift", "inputs": {"model": ["1", 0], "shift_video": 10.0, "shift_audio": 3.0}},
        "3": {"class_type": "ModelAttentionBackend", "inputs": {"model": ["2", 0], "attention": "comfy kitchen attention"}},
        "4": {
            "class_type": "BlockSparseAttention",
            "inputs": {
                "model": ["3", 0], "selection": "vsa", "selection.keep_percent": 10.0,
                "start_percent": 0.2, "end_percent": 1.0, "dense_blocks": "",
                "min_tokens": FAST_VSA_MIN_TOKENS, "extra_tokens": 256,
                "sink_conditioning": "exact_kv_and_rows", "verbose": True,
            },
        },
        "5": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen3vl_32b_minimax_h3_int8_convrot.safetensors", "type": "minimax", "device": "default"}},
        "6": {"class_type": "VAELoader", "inputs": {"vae_name": "minimax_h3_video_vae_fp16.safetensors"}},
        "7": {"class_type": "VAELoader", "inputs": {"vae_name": "minimax_h3_audio_vae_fp32.safetensors"}},
        "8": {"class_type": "MiniMaxH3ImageToVideo", "inputs": {"clip": ["5", 0], "vae": ["6", 0], "prompt": prompt, "width": width, "height": height, "length": grid_frames(duration)}},
        "9": {"class_type": "BasicGuider", "inputs": {"model": ["4", 0], "conditioning": ["8", 0]}},
        "10": {"class_type": "RandomNoise", "inputs": {"noise_seed": random.SystemRandom().randrange(0, 2**63 - 1)}},
        "11": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "res_multistep"}},
        "12": {"class_type": "BasicScheduler", "inputs": {"model": ["4", 0], "scheduler": "simple", "steps": 8, "denoise": 1.0}},
        "13": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["10", 0], "guider": ["9", 0], "sampler": ["11", 0], "sigmas": ["12", 0], "latent_image": ["8", 1]}},
        "14": {"class_type": "VAEDecode", "inputs": {"samples": ["13", 0], "vae": ["6", 0]}},
        "15": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["13", 0], "vae": ["7", 0]}},
        "16": {"class_type": "CreateVideo", "inputs": {"images": ["14", 0], "audio": ["15", 0], "fps": 24.0, "bit_depth": "auto", "color_space": "sRGB", "codec": "auto"}},
        "17": {"class_type": "SaveVideo", "inputs": {"video": ["16", 0], "filename_prefix": f"aid-studio/fast-h3-{int(time.time())}", "format": "mp4", "format.codec": "h264"}},
    }


def build_fast_veda_workflow(prompt: str, width: int, height: int, duration: int) -> dict:
    """Low-resolution H3 Turbo preview using the SM86 Veda predictor."""
    return {
        "1": {"class_type": "UNETLoader", "inputs": {"unet_name": "minimax_h3_fl2va_pruned_int8_convrot.safetensors", "weight_dtype": "default"}},
        "2": {"class_type": "LoraLoaderModelOnly", "inputs": {"model": ["1", 0], "lora_name": TURBO_FL2V_LORA, "strength_model": 1.0}},
        "3": {"class_type": "MiniMaxH3SigmaShift", "inputs": {"model": ["2", 0], "shift_video": 6.0, "shift_audio": 3.0}},
        "4": {"class_type": "VedaSparseAttention", "inputs": {"model": ["3", 0], "predictor": VEDA_PREDICTOR, "generated_sparsity": "90%", "reference_sparsity": "0%", "full_attention_layers": "", "full_attention_steps": "", "verbose": True}},
        "5": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen3vl_32b_minimax_h3_int8_convrot.safetensors", "type": "minimax", "device": "default"}},
        "6": {"class_type": "VAELoader", "inputs": {"vae_name": "minimax_h3_video_vae_fp16.safetensors"}},
        "7": {"class_type": "VAELoader", "inputs": {"vae_name": "minimax_h3_audio_vae_fp32.safetensors"}},
        "8": {"class_type": "MiniMaxH3ImageToVideo", "inputs": {"clip": ["5", 0], "vae": ["6", 0], "prompt": prompt, "width": width, "height": height, "length": grid_frames(duration)}},
        "9": {"class_type": "BasicGuider", "inputs": {"model": ["4", 0], "conditioning": ["8", 0]}},
        "10": {"class_type": "RandomNoise", "inputs": {"noise_seed": random.SystemRandom().randrange(0, 2**63 - 1)}},
        "11": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "12": {"class_type": "BasicScheduler", "inputs": {"model": ["4", 0], "scheduler": "simple", "steps": 8, "denoise": 1.0}},
        "13": {"class_type": "SamplerCustomAdvanced", "inputs": {"noise": ["10", 0], "guider": ["9", 0], "sampler": ["11", 0], "sigmas": ["12", 0], "latent_image": ["8", 1]}},
        "14": {"class_type": "LTXVSeparateAVLatent", "inputs": {"av_latent": ["13", 0]}},
        "15": {"class_type": "VAEDecodeTiled", "inputs": {"samples": ["14", 0], "vae": ["6", 0], "tile_size": 256, "overlap": 64, "temporal_size": 16, "temporal_overlap": 4}},
        "16": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["14", 1], "vae": ["7", 0]}},
        "17": {"class_type": "CreateVideo", "inputs": {"images": ["15", 0], "audio": ["16", 0], "fps": 24.0, "bit_depth": 8, "color_space": "sRGB", "codec": "h264"}},
        "18": {"class_type": "SaveVideo", "inputs": {"video": ["17", 0], "filename_prefix": f"aid-studio/veda-fast-{int(time.time())}", "format": "mp4", "format.codec": "h264"}},
    }


def parse_content(content: object) -> tuple[str, dict[str, list[str]]]:
    prompt = ""
    media: dict[str, list[str]] = {
        "first_frame": [],
        "last_frame": [],
        "reference_image": [],
        "reference_video": [],
        "reference_audio": [],
    }
    if not isinstance(content, list):
        raise AdapterError(400, "content must be an array")
    for item in content:
        if not isinstance(item, dict):
            continue
        if item.get("type") == "text":
            prompt = str(item.get("text", "")).strip()
        elif item.get("type") in {"image_url", "video_url", "audio_url"}:
            media_type = str(item.get("type"))
            target = item.get(media_type)
            url = target.get("url") if isinstance(target, dict) else None
            default_role = {
                "image_url": "reference_image",
                "video_url": "reference_video",
                "audio_url": "reference_audio",
            }[media_type]
            role = str(item.get("role", default_role))
            if url and role in media:
                media[role].append(str(url))
    if not prompt:
        raise AdapterError(400, "Video prompt is required")
    if len(prompt) > 7000:
        raise AdapterError(400, "Video prompt exceeds 7000 characters")
    if len(media["first_frame"]) > 1 or len(media["last_frame"]) > 1:
        raise AdapterError(400, "First-frame and last-frame inputs accept one image each")
    if len(media["reference_image"]) > MAX_REFERENCE_IMAGES:
        raise AdapterError(400, f"Reference images exceed the local limit of {MAX_REFERENCE_IMAGES}")
    if len(media["reference_video"]) > MAX_REFERENCE_VIDEOS:
        raise AdapterError(400, f"Reference videos exceed the local limit of {MAX_REFERENCE_VIDEOS}")
    if len(media["reference_audio"]) > MAX_REFERENCE_AUDIOS:
        raise AdapterError(400, f"Reference audios exceed the local limit of {MAX_REFERENCE_AUDIOS}")
    if sum(len(values) for values in media.values()) > 12:
        raise AdapterError(400, "Reference media exceed the combined limit of 12")
    return prompt, media


def load_tasks() -> dict:
    try:
        return json.loads(TASKS_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_task(task_id: str, metadata: dict) -> None:
    with TASK_LOCK:
        tasks = load_tasks()
        tasks[task_id] = metadata
        TASKS_FILE.parent.mkdir(parents=True, exist_ok=True)
        temp = TASKS_FILE.with_suffix(".tmp")
        temp.write_text(json.dumps(tasks, ensure_ascii=False, indent=2))
        temp.replace(TASKS_FILE)


def scheduler_task_id(job_id: int) -> str:
    return f"gpu-scheduler-{int(job_id)}"


def scheduler_idempotency_key(body: dict, supplied: str | None) -> str:
    value = str(supplied or "").strip()
    if value:
        return value
    digest = hashlib.sha256(
        json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        + uuid.uuid4().bytes
    ).hexdigest()
    return "aid-h3-" + digest[:40]


def require_scheduler_profile(client: GpuSchedulerClient, profile_name: str,
                              display_name: str) -> None:
    profiles = client.profiles()
    profile = profiles.get(profile_name) if isinstance(profiles, dict) else None
    if not isinstance(profile, dict) or not profile.get("enabled", True):
        raise AdapterError(503, f"{display_name} GPU profile is not available")
    snapshot = client.snapshot()
    nodes = snapshot.get("nodes", []) if isinstance(snapshot, dict) else []
    if not any(str(node.get("status", "")).upper() == "ONLINE" for node in nodes if isinstance(node, dict)):
        raise AdapterError(503, f"{display_name} GPU node is offline")


def require_fast_vsa_scheduler(client: GpuSchedulerClient) -> None:
    require_scheduler_profile(client, FAST_VSA_SCHEDULER_PROFILE, "FastH3")


def submit_fast_vsa_scheduler(body: dict, metadata: dict, idempotency_key: str | None) -> str:
    return submit_scheduler_job(
        body, metadata, idempotency_key, FAST_VSA_SCHEDULER_PROFILE, "FastH3"
    )


def submit_scheduler_job(body: dict, metadata: dict, idempotency_key: str | None,
                         profile_name: str, display_name: str) -> str:
    try:
        client = GpuSchedulerClient.from_environment()
        require_scheduler_profile(client, profile_name, display_name)
        video_request = json.loads(json.dumps(body, ensure_ascii=False))
        video_request.update({
            "model": "MiniMax-H3",
            "resolution": metadata["resolution"],
            "duration": metadata["duration"],
            "acceleration": metadata["acceleration"],
        })
        job = client.submit(
            profile_name,
            {"videoRequest": video_request},
            scheduler_idempotency_key(video_request, idempotency_key),
        )
    except SchedulerApiError as exc:
        raise AdapterError(503, f"GPU scheduler unavailable: {exc.code}") from exc
    except (OSError, RuntimeError, ValueError) as exc:
        raise AdapterError(503, "GPU scheduler is not configured") from exc
    job_id = job.get("id") if isinstance(job, dict) else None
    if not isinstance(job_id, int) or job_id <= 0:
        raise AdapterError(502, "GPU scheduler did not return a job id")
    task_id = scheduler_task_id(job_id)
    metadata.update({
        "backend": "gpu_scheduler",
        "scheduler_job_id": job_id,
        "scheduler_profile": profile_name,
    })
    save_task(task_id, metadata)
    return task_id


def require_scheduler_route(body: dict) -> None:
    """Fail closed before a request can touch a private GPU worker."""
    if not GPU_SCHEDULER_REQUIRED:
        return
    acceleration = str(body.get("acceleration", "stable")).strip().lower() or "stable"
    if acceleration == "fast_vsa" and FAST_VSA_SCHEDULER_ENABLED:
        return
    if acceleration in {"stable", "turbo"} and QUALITY_SCHEDULER_ENABLED:
        return
    if acceleration == "fast_veda" and VEDA_SCHEDULER_ENABLED:
        return
    if acceleration not in {"fast_vsa", "stable", "turbo"}:
        raise AdapterError(
            503,
            f"H3 {acceleration} 尚未完成 GPU 调度器验收，请暂用 FastH3 快速预览",
        )
    raise AdapterError(503, f"H3 {acceleration} GPU 调度通道未启用")


def scheduler_quality_metadata(body: dict) -> dict:
    prompt, media = parse_content(body.get("content"))
    del prompt
    acceleration = str(body.get("acceleration", "stable")).strip().lower() or "stable"
    if acceleration not in {"stable", "turbo"}:
        raise AdapterError(400, "Quality scheduler supports stable or turbo")
    if str(body.get("operation", "generate")).strip().lower() != "generate":
        raise AdapterError(400, "Scheduled H3 quality currently supports video generation only")
    duration = int(body.get("duration", 5))
    if duration < 4 or duration > MAX_SAFE_DURATION:
        raise AdapterError(400, f"Scheduled H3 quality supports 4 to {MAX_SAFE_DURATION} seconds")
    resolution = str(body.get("resolution", "768P")).strip().upper()
    if resolution not in RESOLUTION_SIZES:
        raise AdapterError(400, f"Unsupported resolution: {resolution}")
    if resolution == "768P" and duration > 5:
        raise AdapterError(400, "RTX 3090 的 768P 单段最多 5 秒，请先拆分镜头后再生成")
    ratio = str(body.get("ratio", "16:9"))
    if ratio not in {*RESOLUTION_SIZES[resolution], "adaptive"}:
        raise AdapterError(400, f"Unsupported ratio: {ratio}")
    image_count = sum(len(media[key]) for key in ("first_frame", "last_frame", "reference_image"))
    return {
        "duration": duration,
        "requested_duration": duration,
        "duration_capped": False,
        "input_image_count": image_count,
        "input_video_seconds": 0,
        "reference_mode": bool(media["reference_video"] or media["reference_audio"] or
                               len(media["reference_image"]) > 1),
        "operation": "generate",
        "resolution": resolution,
        "requested_resolution": resolution,
        "native_audio": True,
        "acceleration": acceleration,
        "created_at": int(time.time()),
    }


def build_workflow(body: dict) -> tuple[dict, dict]:
    if body.get("model") != "MiniMax-H3":
        raise AdapterError(400, "Only MiniMax-H3 is supported")
    prompt, media = parse_content(body.get("content"))
    acceleration = str(body.get("acceleration", "stable")).strip().lower() or "stable"
    if acceleration not in {"stable", "turbo", "fast_vsa", "fast_veda"}:
        raise AdapterError(400, "Unsupported H3 acceleration profile")
    if acceleration == "turbo" and not TURBO_ENABLED:
        raise AdapterError(503, "H3 Turbo LoRA is not enabled on this server")
    if acceleration == "fast_vsa" and not FAST_VSA_ENABLED:
        raise AdapterError(503, "FastH3 + VSA is not enabled on this server")
    if acceleration == "fast_veda" and not VEDA_ENABLED:
        raise AdapterError(503, "H3 Veda FAST is not enabled on this server")
    operation = str(body.get("operation", "generate")).strip().lower()
    if operation not in {"generate", "face_swap"}:
        raise AdapterError(400, "Unsupported video operation")
    if operation == "face_swap" and not FACE_SWAP_ENABLED:
        raise AdapterError(400, "Local face swap beta is disabled")
    requested_duration = int(body.get("duration", 5))
    if requested_duration < 4 or requested_duration > 15:
        raise AdapterError(400, "duration must be between 4 and 15 seconds")
    duration = requested_duration
    duration_capped = requested_duration > MAX_SAFE_DURATION and LONG_SHOT_POLICY == "cap"
    if requested_duration > MAX_SAFE_DURATION and LONG_SHOT_POLICY == "reject":
        raise AdapterError(
            400,
            f"Local RTX 3090 supports at most {MAX_SAFE_DURATION} seconds; split the shot and retry",
        )
    if duration_capped:
        duration = MAX_SAFE_DURATION
        prompt += (
            f"\n时长适配：原镜头为{requested_duration}秒，请在{duration}秒内压缩完成全部核心动作，"
            "保持叙事连续，不要省略结尾动作。"
        )
    requested_resolution = str(body.get("resolution", "768P")).strip().upper()
    if requested_resolution not in RESOLUTION_SIZES:
        raise AdapterError(400, f"Unsupported resolution: {requested_resolution}")
    # 24 GiB RTX 3090 在 H3 6 秒 768P 的采样阶段已稳定复现 OOM；VAE 分块只影响
    # 解码峰值，无法降低 SamplerCustomAdvanced 的 latent 峰值。禁止静默降到 480P：
    # 这会让前端仍显示 768P、实际成片却损失清晰度。生产链应先把长镜头拆成
    # 不超过 5 秒的片段，再逐段按用户选择的 768P 生成。
    if requested_resolution == "768P" and duration >= 6 and acceleration not in {"fast_vsa", "fast_veda"}:
        raise AdapterError(400, "RTX 3090 的 768P 单段最多 5 秒，请先拆分镜头后再生成")
    resolution = requested_resolution
    if acceleration in {"fast_vsa", "fast_veda"}:
        # The 3090 production preset is benchmarked at 832x480. Keep the preview
        # route on that safe canvas; upscale after generation when needed.
        resolution = "480P"
    ratio = str(body.get("ratio", "16:9"))
    if ratio not in {*RESOLUTION_SIZES[resolution], "adaptive"}:
        raise AdapterError(400, f"Unsupported ratio: {ratio}")

    graph = json.loads(WORKFLOW_FILE.read_text())["prompt"]
    source_size = None
    image_count = 0
    input_video_seconds = 0.0

    frame_images = media["first_frame"] + media["last_frame"]
    reference_images = media["reference_image"]
    reference_videos = media["reference_video"]
    reference_audios = media["reference_audio"]
    reference_mode = bool(reference_videos or reference_audios or len(reference_images) > 1)

    if acceleration == "fast_vsa":
        if operation != "generate" or any(media.values()):
            raise AdapterError(400, "FastH3 + VSA only supports text-to-video; use Turbo for reference media")
        width, height = RESOLUTION_SIZES[resolution]["16:9" if ratio == "adaptive" else ratio]
        graph = build_fast_vsa_workflow(prompt, width, height, duration)
        return graph, {
            "duration": duration,
            "requested_duration": requested_duration,
            "duration_capped": duration_capped,
            "input_image_count": 0,
            "input_video_seconds": 0,
            "reference_mode": False,
            "operation": operation,
            "resolution": resolution,
            "requested_resolution": requested_resolution,
            "width": width,
            "height": height,
            "vae_decode": "gpu",
            "native_audio": True,
            "acceleration": "fast_vsa",
            "comfy_url": ACCEL_COMFY_URL,
            "created_at": int(time.time()),
        }

    if acceleration == "fast_veda":
        if operation != "generate" or any(media.values()):
            raise AdapterError(400, "H3 Veda FAST only supports text-to-video; use Turbo for reference media")
        width, height = RESOLUTION_SIZES[resolution]["16:9" if ratio == "adaptive" else ratio]
        graph = build_fast_veda_workflow(prompt, width, height, duration)
        return graph, {
            "duration": duration,
            "requested_duration": requested_duration,
            "duration_capped": duration_capped,
            "input_image_count": 0,
            "input_video_seconds": 0,
            "reference_mode": False,
            "operation": operation,
            "resolution": resolution,
            "requested_resolution": requested_resolution,
            "width": width,
            "height": height,
            "vae_decode": "gpu_tiled",
            "native_audio": True,
            "acceleration": "fast_veda",
            "comfy_url": VEDA_COMFY_URL,
            "created_at": int(time.time()),
        }

    if operation == "face_swap":
        if frame_images or len(reference_images) != 1 or len(reference_videos) != 1:
            raise AdapterError(400, "Face swap requires exactly one source face image and one target video")
        if reference_audios:
            raise AdapterError(400, "Face swap preserves target audio and does not accept reference audio")
        image_data, image_suffix, image_content_type = download_media(reference_images[0])
        source_name, source_size = upload_image(image_data, image_suffix, image_content_type)
        video_data, video_suffix, video_content_type = download_media(
            reference_videos[0], max_bytes=MAX_VIDEO_DOWNLOAD, media_label="Target video"
        )
        video_data, video_suffix, video_content_type, prepared_seconds = prepare_reference_video(
            video_data, video_suffix, duration
        )
        target_name = upload_media(
            video_data, video_suffix, video_content_type, prefix="aid-face-swap-video"
        )
        graph = {
            "1": {"class_type": "LoadVideo", "inputs": {"file": target_name}},
            "2": {"class_type": "GetVideoComponents", "inputs": {"video": ["1", 0]}},
            "3": {"class_type": "LoadImage", "inputs": {"image": source_name}},
            "4": {
                "class_type": "ReActorFaceSwap",
                "inputs": {
                    "enabled": True,
                    "input_image": ["2", 0],
                    "source_image": ["3", 0],
                    "swap_model": "inswapper_128.onnx",
                    "facedetection": "retinaface_resnet50",
                    "face_restore_model": "GFPGANv1.4.pth",
                    "face_restore_visibility": 0.35,
                    "codeformer_weight": 0.5,
                    "detect_gender_input": "no",
                    "detect_gender_source": "no",
                    "input_faces_index": "0",
                    "source_faces_index": "0",
                    "console_log_level": 1,
                },
            },
            "5": {
                "class_type": "CreateVideo",
                "inputs": {"images": ["4", 0], "audio": ["2", 1], "fps": 24.0, "bit_depth": 8},
            },
            "6": {
                "class_type": "SaveVideo",
                "inputs": {
                    "video": ["5", 0],
                    "filename_prefix": f"aid-studio/face-swap-{int(time.time())}",
                    "format": "mp4",
                    "codec": "h264",
                },
            },
        }
        return graph, {
            "duration": duration,
            "requested_duration": requested_duration,
            "duration_capped": duration_capped,
            "input_image_count": 1,
            "input_video_seconds": math.ceil(prepared_seconds),
            "reference_mode": True,
            "operation": operation,
            "resolution": resolution,
            "requested_resolution": requested_resolution,
            "width": source_size[0],
            "height": source_size[1],
            "native_audio": True,
            "created_at": int(time.time()),
        }

    if frame_images and (reference_images or reference_videos or reference_audios):
        raise AdapterError(400, "First/last-frame inputs cannot be combined with reference media")
    if reference_audios and not (reference_images or reference_videos):
        raise AdapterError(400, "Reference audio requires at least one reference image or video")

    # AID 的“多参生视频”在只有一张主参考图时仍使用 reference_image。
    # 本地 H3 工作流没有独立的多参考图条件节点，但单图可以无损地作为
    # 首帧条件使用；这样场景/角色主图仍会参与生成，而不是在提交阶段失败。
    next_node = 18
    if reference_mode:
        graph["1"]["inputs"]["unet_name"] = "minimax_h3_ref2va_pruned_int8_convrot.safetensors"
        graph["6"] = {
            # ComfyUI 0.39 normalizes the stock node's autogrow reference ports
            # correctly. Use it directly: the legacy Linjian wrapper still calls
            # the built-in node with the pre-0.39 positional argument order and
            # shifts prompt/width/height into the wrong parameters at runtime.
            "class_type": "MiniMaxH3ReferenceToVideo",
            "inputs": {
                "clip": ["3", 0],
                "vae": ["4", 0],
                "audio_vae": ["16", 0],
                "prompt": prompt,
                "width": 1344,
                "height": 768,
                "length": 124,
                "ref_image_size": "match",
            },
        }
        for index, url in enumerate(reference_images):
            data, suffix, content_type = download_media(url)
            remote_name, image_size = upload_image(data, suffix, content_type)
            source_size = source_size or image_size
            node_id = str(next_node)
            graph[node_id] = {"class_type": "LoadImage", "inputs": {"image": remote_name}}
            graph["6"]["inputs"][f"ref_images.ref_image_{index}"] = [node_id, 0]
            next_node += 1
            image_count += 1
        for index, url in enumerate(reference_videos):
            data, suffix, content_type = download_media(
                url, max_bytes=MAX_VIDEO_DOWNLOAD, media_label="Reference video"
            )
            data, suffix, content_type, prepared_seconds = prepare_reference_video(
                data, suffix, duration
            )
            remote_name = upload_media(data, suffix, content_type, prefix="aid-h3-video")
            load_id = str(next_node)
            components_id = str(next_node + 1)
            graph[load_id] = {"class_type": "LoadVideo", "inputs": {"file": remote_name}}
            graph[components_id] = {"class_type": "GetVideoComponents", "inputs": {"video": [load_id, 0]}}
            graph["6"]["inputs"][f"ref_videos.ref_video_{index}"] = [components_id, 0]
            graph["6"]["inputs"][f"ref_video_audios.ref_video_audio_{index}"] = [components_id, 1]
            next_node += 2
            input_video_seconds += prepared_seconds
        for index, url in enumerate(reference_audios):
            data, suffix, content_type = download_media(
                url, max_bytes=MAX_AUDIO_DOWNLOAD, media_label="Reference audio"
            )
            remote_name = upload_media(data, suffix, content_type, prefix="aid-h3-audio")
            node_id = str(next_node)
            graph[node_id] = {"class_type": "LoadAudio", "inputs": {"audio": remote_name}}
            graph["6"]["inputs"][f"ref_audios.ref_audio_{index}"] = [node_id, 0]
            next_node += 1
    else:
        # 单张普通参考图沿用更省显存的 I2V 路径。
        if reference_images and not frame_images:
            media["first_frame"] = [reference_images[0]]
        for role, input_name in (("first_frame", "first_frame"), ("last_frame", "last_frame")):
            if not media[role]:
                continue
            data, suffix, content_type = download_media(media[role][0])
            remote_name, image_size = upload_image(data, suffix, content_type)
            source_size = source_size or image_size
            node_id = str(next_node)
            graph[node_id] = {"class_type": "LoadImage", "inputs": {"image": remote_name}}
            graph["6"]["inputs"][input_name] = [node_id, 0]
            next_node += 1
            image_count += 1
    ratio_sizes = RESOLUTION_SIZES[resolution]
    width, height = adaptive_size(source_size, resolution) if ratio == "adaptive" else ratio_sizes[ratio]
    if width % 32 or height % 32 or width > 1344 or height > 1344 or width * height > 768 * 1344:
        raise AdapterError(500, "Internal H3 canvas constraint violation")

    graph["6"]["inputs"].update({"prompt": prompt, "width": width, "height": height, "length": grid_frames(duration)})
    if acceleration == "turbo":
        lora_name = TURBO_REF2V_LORA if reference_mode else TURBO_FL2V_LORA
        steps = 4 if reference_mode else 8
        video_shift = 12.0 if reference_mode else 6.0
        graph["90"] = {
            "class_type": "LoraLoaderModelOnly",
            "inputs": {"model": ["1", 0], "lora_name": lora_name, "strength_model": 1.0},
        }
        graph["2"]["inputs"].update({"model": ["90", 0], "shift_video": video_shift, "shift_audio": 3.0})
        graph["8"]["inputs"]["sampler_name"] = "euler"
        graph["10"]["inputs"]["steps"] = steps
    graph["7"]["inputs"]["noise_seed"] = random.SystemRandom().randrange(0, 2**63 - 1)
    graph["15"]["inputs"]["filename_prefix"] = f"aid-studio/h3-{int(time.time())}"
    return graph, {
        "duration": duration,
        "requested_duration": requested_duration,
        "duration_capped": duration_capped,
        "input_image_count": image_count,
        "input_video_seconds": math.ceil(input_video_seconds),
        "reference_mode": reference_mode,
        "operation": operation,
        "resolution": resolution,
        "requested_resolution": requested_resolution,
        "width": width,
        "height": height,
        "vae_decode": "gpu_tiled",
        "native_audio": True,
        "acceleration": acceleration,
        "comfy_url": COMFY_URL,
        "created_at": int(time.time()),
    }


def output_url(history: dict, comfy_url: str = COMFY_URL) -> str | None:
    for output in history.get("outputs", {}).values():
        if not isinstance(output, dict):
            continue
        for key in ("videos", "gifs", "images"):
            values = output.get(key)
            if not isinstance(values, list):
                continue
            for value in values:
                if not isinstance(value, dict) or not value.get("filename"):
                    continue
                query = urllib.parse.urlencode({
                    "filename": value["filename"],
                    "subfolder": value.get("subfolder", ""),
                    "type": value.get("type", "output"),
                })
                return comfy_url + "/view?" + query
    return None


def attach_submission_marker(graph: dict) -> str:
    """Add a unique output marker so an accepted prompt can be recovered after an HTTP timeout."""
    marker = "aid-submit-" + uuid.uuid4().hex
    for node in graph.values():
        if not isinstance(node, dict) or node.get("class_type") != "SaveVideo":
            continue
        inputs = node.get("inputs")
        if not isinstance(inputs, dict):
            continue
        prefix = str(inputs.get("filename_prefix") or "aid-studio/h3")
        inputs["filename_prefix"] = f"{prefix}-{marker}"
        return marker
    raise AdapterError(500, "H3 workflow has no video output node")


def queued_prompt_id(queue: dict, marker: str) -> str | None:
    """Find a Comfy prompt that was accepted even though the submit response timed out."""
    for key in ("queue_running", "queue_pending"):
        for item in queue.get(key, []):
            if not isinstance(item, list) or len(item) < 3:
                continue
            if marker in json.dumps(item[2], ensure_ascii=False):
                return str(item[1])
    return None


def recover_accepted_prompt_id(comfy_url: str, marker: str) -> str | None:
    try:
        return queued_prompt_id(http_json(comfy_url + "/queue", timeout=45), marker)
    except Exception:
        return None


def workflow_error(history: dict, *, reference_mode: bool = False) -> dict:
    status_info = history.get("status", {}) if isinstance(history, dict) else {}
    for kind, payload in reversed(status_info.get("messages", [])):
        if kind != "execution_error" or not isinstance(payload, dict):
            continue
        exception_type = str(payload.get("exception_type", ""))
        exception_message = str(payload.get("exception_message", ""))
        combined = (exception_type + " " + exception_message).lower()
        if "outofmemory" in combined or "out of memory" in combined:
            return {
                "code": "GPU_OUT_OF_MEMORY",
                "message": (
                    "参考视频条件占用显存过高，请缩短参考片段、降低分辨率或改用普通图生视频"
                    if reference_mode
                    else "GPU显存不足，请缩短至10秒以内、降低分辨率或拆分镜头后重试"
                ),
            }
        if "nestedtensor" in combined and str(payload.get("node_type")) == "VAEDecodeTiled":
            return {
                "code": "VAE_DECODE_INCOMPATIBLE",
                "message": "VAE分块解码不兼容，请切换解码工作流后重试",
            }
        return {
            "code": "COMFY_WORKFLOW_FAILED",
            "message": "ComfyUI工作流执行失败，请检查节点日志",
        }
    return {"code": "COMFY_WORKFLOW_FAILED", "message": "ComfyUI工作流执行失败，请检查节点日志"}


def task_response(task_id: str) -> dict:
    metadata = load_tasks().get(task_id, {})
    if metadata.get("backend") == "gpu_scheduler":
        return scheduler_task_response(task_id, metadata)
    comfy_url = str(metadata.get("comfy_url") or COMFY_URL).rstrip("/")
    # If comfy_url is missing from metadata (old tasks), try both instances
    candidate_urls = [comfy_url]
    if not metadata.get("comfy_url"):
        for alt in (COMFY_URL, ACCEL_COMFY_URL, VEDA_COMFY_URL):
            alt = alt.rstrip("/")
            if alt not in candidate_urls:
                candidate_urls.append(alt)
    history = None
    resolved_url = comfy_url
    for url in candidate_urls:
        try:
            history_all = http_json(url + "/history/" + urllib.parse.quote(task_id), timeout=15)
        except Exception:
            continue
        if task_id in history_all:
            history = history_all[task_id]
            resolved_url = url
            break
    status = "queued"
    content: dict = {}
    error: dict | None = None
    if isinstance(history, dict):
        status_info = history.get("status", {})
        status_text = str(status_info.get("status_str", "")).lower()
        completed = bool(status_info.get("completed"))
        url = output_url(history, resolved_url)
        if completed and url:
            status = "succeeded"
            content = {"url": url}
        elif status_text in {"error", "failed"}:
            status = "failed"
            error = workflow_error(history, reference_mode=bool(metadata.get("reference_mode")))
        else:
            status = "running"
    else:
        # Not in any history — check queues, then check staleness
        found_in_queue = False
        for url in candidate_urls:
            try:
                queue = http_json(url + "/queue", timeout=15)
            except Exception:
                continue
            ids = {str(item[1]) for key in ("queue_running", "queue_pending") for item in queue.get(key, []) if len(item) > 1}
            if task_id in ids:
                found_in_queue = True
                resolved_url = url
                break
        if found_in_queue:
            status = "running"
        else:
            # Task not in history or queue on any instance
            created = metadata.get("created_at", 0)
            age_seconds = int(time.time()) - created if created else 0
            # Long H3 runs can briefly disappear from /queue before ComfyUI commits
            # their successful /history record.  Keep this grace longer than the
            # production scheduler's two-hour lifetime so that transition is not
            # misreported as a terminal failure.
            if age_seconds > TASK_LOST_TIMEOUT_SECONDS:
                status = "failed"
                error = {"code": "TASK_LOST", "message": f"任务在 ComfyUI 中丢失（超过 {age_seconds // 60} 分钟无响应），可能实例已重启，请重新生成"}
            else:
                status = "queued"

    task = {
        "id": task_id,
        "model": "MiniMax-H3",
        "task_type": "generation",
        "modality": "video",
        "status": status,
        "content": content,
        "duration": metadata.get("duration", 5),
        "resolution": metadata.get("resolution", "768P"),
        "acceleration": metadata.get("acceleration", "stable"),
        "usage": {
            "output_seconds": metadata.get("duration", 5) if status == "succeeded" else None,
            "input_seconds": metadata.get("input_video_seconds", 0),
            "input_image_count": metadata.get("input_image_count", 0),
        },
    }
    if error:
        task["error"] = error
    if status in {"succeeded", "failed"} and not metadata.get("warm_release_scheduled_at"):
        schedule_comfy_release(
            resolved_url,
            delay_seconds=keep_warm_seconds_for(metadata),
        )
        metadata["warm_release_scheduled_at"] = int(time.time())
        save_task(task_id, metadata)
    return {"task": task}


def scheduler_task_response(task_id: str, metadata: dict) -> dict:
    try:
        job = GpuSchedulerClient.from_environment().get_job(int(metadata["scheduler_job_id"]))
    except SchedulerApiError as exc:
        raise AdapterError(503, f"GPU scheduler unavailable: {exc.code}") from exc
    except (KeyError, OSError, RuntimeError, TypeError, ValueError) as exc:
        raise AdapterError(503, "GPU scheduler query is unavailable") from exc
    scheduler_status = str(job.get("status") or "").upper()
    status = {
        "QUEUED": "queued",
        "RUNNING": "running",
        "SUCCEEDED": "succeeded",
        "FAILED": "failed",
        "CANCELLED": "failed",
        "LOST": "failed",
    }.get(scheduler_status, "queued")
    result = job.get("result") if isinstance(job.get("result"), dict) else {}
    result_url = str(result.get("resultRef") or "").strip()
    if status == "succeeded" and not result_url:
        status = "running"
    task = {
        "id": task_id,
        "model": "MiniMax-H3",
        "task_type": "generation",
        "modality": "video",
        "status": status,
        "content": {"url": result_url} if status == "succeeded" else {},
        "duration": metadata.get("duration", 5),
        "resolution": metadata.get("resolution", "480P"),
        "acceleration": metadata.get("acceleration", result.get("acceleration", "stable")),
        "usage": {
            "output_seconds": metadata.get("duration", 5) if status == "succeeded" else None,
            "input_seconds": result.get("inputSeconds", metadata.get("input_video_seconds", 0)),
            "input_image_count": result.get("inputImageCount", metadata.get("input_image_count", 0)),
        },
    }
    if status == "failed":
        code = {
            "CANCELLED": "GPU_JOB_CANCELLED",
            "LOST": "GPU_JOB_LOST",
        }.get(scheduler_status, "GPU_JOB_FAILED")
        task["error"] = {"code": code, "message": "GPU调度任务未完成，请稍后重试"}
    return {"task": task}


def health() -> dict:
    scheduler_health = {
        "enabled": (
            FAST_VSA_SCHEDULER_ENABLED or QUALITY_SCHEDULER_ENABLED or
            VEDA_SCHEDULER_ENABLED
        ),
        "available": False,
        "profiles": {},
    }
    if scheduler_health["enabled"]:
        try:
            client = GpuSchedulerClient.from_environment()
            profiles = client.profiles()
            snapshot = client.snapshot()
            nodes = snapshot.get("nodes", []) if isinstance(snapshot, dict) else []
            online = any(str(node.get("status", "")).upper() == "ONLINE"
                         for node in nodes if isinstance(node, dict))
            for name, enabled in (
                (FAST_VSA_SCHEDULER_PROFILE, FAST_VSA_SCHEDULER_ENABLED),
                (QUALITY_SCHEDULER_PROFILE, QUALITY_SCHEDULER_ENABLED),
                (VEDA_SCHEDULER_PROFILE, VEDA_SCHEDULER_ENABLED),
            ):
                profile = profiles.get(name) if isinstance(profiles, dict) else None
                scheduler_health["profiles"][name] = bool(
                    enabled and online and isinstance(profile, dict) and profile.get("enabled", True)
                )
            scheduler_health["available"] = any(scheduler_health["profiles"].values())
        except (AdapterError, SchedulerApiError, OSError, RuntimeError, ValueError) as exc:
            scheduler_health["error"] = exc.__class__.__name__
    required = {
        "MiniMaxH3ImageToVideo",
        "MiniMaxH3SigmaShift",
        "UNETLoader",
        "CLIPLoader",
        "VAELoaderKJ",
        "VAEDecodeTiled",
        "VAEDecodeAudio",
        "LTXVSeparateAVLatent",
        "SaveVideo",
    }
    stats: dict = {}
    available: set[str] = set()
    direct_error: AdapterError | None = None
    if GPU_SCHEDULER_REQUIRED:
        direct_error = AdapterError(503, "Direct GPU worker access is disabled")
    else:
        try:
            direct_timeout = 3 if scheduler_health["available"] else 10
            stats = http_json(COMFY_URL + "/system_stats", timeout=direct_timeout)
            available = set(http_json(COMFY_URL + "/object_info", timeout=direct_timeout))
            missing = sorted(required - available)
            if missing:
                raise AdapterError(503, "Missing ComfyUI nodes: " + ", ".join(missing))
        except AdapterError as exc:
            direct_error = exc
            if not scheduler_health["available"]:
                raise
    direct_available = direct_error is None
    return {
        "ok": True,
        "comfy_url": COMFY_URL,
        "comfy_version": stats.get("system", {}).get("comfyui_version"),
        "comfy_available": direct_available,
        "missing_nodes": [] if direct_available else None,
        "resolution_profiles": list(SUPPORTED_RESOLUTIONS),
        "vae_decode": "gpu_tiled",
        "native_audio": True,
        "reference_video": direct_available and "MiniMaxH3ReferenceToVideo" in available,
        "reference_limits": {
            "images": MAX_REFERENCE_IMAGES,
            "videos": MAX_REFERENCE_VIDEOS,
            "audios": MAX_REFERENCE_AUDIOS,
        },
        "face_swap": direct_available and FACE_SWAP_ENABLED and "ReActorFaceSwap" in available,
        "max_safe_duration": MAX_SAFE_DURATION,
        "long_shot_policy": LONG_SHOT_POLICY,
        "acceleration_profiles": {
            "stable": direct_available or scheduler_health["profiles"].get(QUALITY_SCHEDULER_PROFILE, False),
            "turbo": TURBO_ENABLED and (
                direct_available or scheduler_health["profiles"].get(QUALITY_SCHEDULER_PROFILE, False)
            ),
            "fast_vsa": FAST_VSA_ENABLED and (
                direct_available or scheduler_health["profiles"].get(FAST_VSA_SCHEDULER_PROFILE, False)
            ),
            "fast_veda": VEDA_ENABLED and (
                direct_available or scheduler_health["profiles"].get(VEDA_SCHEDULER_PROFILE, False)
            ),
            "sla": False,
        },
        "acceleration_comfy_url": ACCEL_COMFY_URL if FAST_VSA_ENABLED else None,
        "veda_comfy_url": VEDA_COMFY_URL if VEDA_ENABLED else None,
        "resource_arbiter": {
            "enabled": RESOURCE_RELEASE_ENABLED,
            "peer_count": len(RESOURCE_RELEASE_URLS),
            "keep_warm_seconds": KEEP_WARM_SECONDS,
            "fast_vsa_keep_warm_seconds": FAST_VSA_KEEP_WARM_SECONDS,
            "fast_vsa_min_tokens": FAST_VSA_MIN_TOKENS,
        },
        "gpu_scheduler": scheduler_health,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "AID-ComfyH3/1.0"

    def log_message(self, fmt: str, *args: object) -> None:
        print(time.strftime("%Y-%m-%d %H:%M:%S"), self.address_string(), fmt % args, flush=True)

    def send_json(self, status: int, body: dict) -> None:
        payload = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        try:
            path = urllib.parse.urlparse(self.path).path
            if path == "/health":
                self.send_json(200, health())
                return
            if path == "/v2/query/video_generation":
                # AID Studio probes MiniMax with the provider's read-only task
                # list endpoint. Validate the actual ComfyUI/H3 installation so
                # a successful probe means more than just an open local port.
                health()
                self.send_json(200, {"items": [], "total": 0})
                return
            prefix = "/v2/query/video_generation/"
            if path.startswith(prefix) and len(path) > len(prefix):
                self.send_json(200, task_response(path[len(prefix):]))
                return
            raise AdapterError(404, "Not found")
        except AdapterError as exc:
            self.send_json(exc.status, {"error": str(exc)})
        except Exception as exc:
            self.send_json(500, {"error": f"Adapter error: {exc}"})

    def do_POST(self) -> None:
        try:
            if urllib.parse.urlparse(self.path).path != "/v2/video_generation":
                raise AdapterError(404, "Not found")
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 2 * 1024 * 1024:
                raise AdapterError(400, "Invalid request size")
            body = json.loads(self.rfile.read(length))
            require_scheduler_route(body)
            acceleration = str(body.get("acceleration", "stable")).strip().lower() or "stable"
            if acceleration in {"stable", "turbo"} and QUALITY_SCHEDULER_ENABLED:
                metadata = scheduler_quality_metadata(body)
                task_id = submit_scheduler_job(
                    body, metadata, self.headers.get("Idempotency-Key"),
                    QUALITY_SCHEDULER_PROFILE, "H3 Quality",
                )
                self.send_json(200, {"task_id": task_id})
                return
            if acceleration == "fast_veda" and VEDA_SCHEDULER_ENABLED:
                _graph, metadata = build_workflow(body)
                task_id = submit_scheduler_job(
                    body, metadata, self.headers.get("Idempotency-Key"),
                    VEDA_SCHEDULER_PROFILE, "H3 Veda FAST",
                )
                self.send_json(200, {"task_id": task_id})
                return
            graph, metadata = build_workflow(body)
            if metadata.get("acceleration") == "fast_vsa" and FAST_VSA_SCHEDULER_ENABLED:
                task_id = submit_fast_vsa_scheduler(
                    body, metadata, self.headers.get("Idempotency-Key")
                )
                self.send_json(200, {"task_id": task_id})
                return
            submission_marker = attach_submission_marker(graph)
            comfy_url = str(metadata.get("comfy_url") or COMFY_URL).rstrip("/")
            release_peer_gpu_models()
            with RUNTIME_RELEASE_LOCK:
                cancel_scheduled_comfy_release(comfy_url)
                for peer_url in {COMFY_URL, ACCEL_COMFY_URL, VEDA_COMFY_URL}:
                    if peer_url.rstrip("/") != comfy_url:
                        release_comfy_models(peer_url)
                try:
                    response = http_json(comfy_url + "/prompt", method="POST", body={"prompt": graph, "client_id": "aid-studio-local"}, timeout=60)
                    prompt_id = response.get("prompt_id")
                except AdapterError:
                    # Comfy may block its HTTP response while a large H3 graph is loading,
                    # even after it has already placed the prompt in its execution queue.
                    # Recover that accepted prompt instead of reporting a false failure and
                    # leaving an untracked GPU job running.
                    prompt_id = recover_accepted_prompt_id(comfy_url, submission_marker)
                    if not prompt_id:
                        raise
            if not prompt_id:
                raise AdapterError(502, "ComfyUI did not return prompt_id")
            save_task(str(prompt_id), metadata)
            self.send_json(200, {"task_id": str(prompt_id)})
        except json.JSONDecodeError:
            self.send_json(400, {"error": "Invalid JSON"})
        except AdapterError as exc:
            self.send_json(exc.status, {"error": str(exc)})
        except Exception as exc:
            self.send_json(500, {"error": f"Adapter error: {exc}"})


if __name__ == "__main__":
    RUNTIME.mkdir(parents=True, exist_ok=True)
    print(f"AID ComfyUI H3 adapter listening on http://{LISTEN_HOST}:{LISTEN_PORT}; upstream={COMFY_URL}", flush=True)
    ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler).serve_forever()
