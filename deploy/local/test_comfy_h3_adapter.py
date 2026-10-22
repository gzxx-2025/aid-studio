import unittest
from unittest.mock import patch

from comfy_h3_adapter import (
    AdapterError,
    _release_comfy_if_idle,
    attach_submission_marker,
    build_workflow,
    health,
    keep_warm_seconds_for,
    queued_prompt_id,
    release_peer_gpu_models,
    require_scheduler_route,
    scheduler_quality_metadata,
    scheduler_task_response,
    schedule_comfy_release,
    submit_fast_vsa_scheduler,
    submit_scheduler_job,
    task_response,
    workflow_error,
)


def request(resolution: str, ratio: str = "16:9") -> dict:
    return {
        "model": "MiniMax-H3",
        "duration": 4,
        "resolution": resolution,
        "ratio": ratio,
        "content": [{"type": "text", "text": "A red paper boat drifts across a calm lake."}],
    }


class ComfyH3AdapterWorkflowTest(unittest.TestCase):
    @patch("comfy_h3_adapter.GPU_SCHEDULER_REQUIRED", True)
    def test_scheduler_required_rejects_unapproved_direct_profiles(self):
        for acceleration in ("stable", "turbo"):
            with self.subTest(acceleration=acceleration):
                with self.assertRaises(AdapterError) as raised:
                    require_scheduler_route({"acceleration": acceleration})
                self.assertEqual(503, raised.exception.status)

    @patch("comfy_h3_adapter.FAST_VSA_SCHEDULER_ENABLED", True)
    @patch("comfy_h3_adapter.GPU_SCHEDULER_REQUIRED", True)
    def test_scheduler_required_allows_only_validated_fast_profile(self):
        require_scheduler_route({"acceleration": "fast_vsa"})

    @patch("comfy_h3_adapter.QUALITY_SCHEDULER_ENABLED", True)
    @patch("comfy_h3_adapter.GPU_SCHEDULER_REQUIRED", True)
    def test_scheduler_required_allows_validated_quality_profiles(self):
        require_scheduler_route({"acceleration": "stable"})
        require_scheduler_route({"acceleration": "turbo"})

    @patch("comfy_h3_adapter.VEDA_SCHEDULER_ENABLED", True)
    @patch("comfy_h3_adapter.GPU_SCHEDULER_REQUIRED", True)
    def test_scheduler_required_allows_validated_veda_profile(self):
        require_scheduler_route({"acceleration": "fast_veda"})

    def test_quality_metadata_validates_references_without_uploading_them(self):
        payload = request("480P")
        payload["acceleration"] = "turbo"
        payload["content"].extend([
            {"type": "image_url", "role": "reference_image",
             "image_url": {"url": "https://example.test/one.png"}},
            {"type": "image_url", "role": "reference_image",
             "image_url": {"url": "https://example.test/two.png"}},
        ])
        metadata = scheduler_quality_metadata(payload)
        self.assertEqual(2, metadata["input_image_count"])
        self.assertTrue(metadata["reference_mode"])
        self.assertEqual("turbo", metadata["acceleration"])

    @patch("comfy_h3_adapter.save_task")
    @patch("comfy_h3_adapter.GpuSchedulerClient.from_environment")
    def test_quality_submission_uses_standard_profile(self, from_environment, save_task_mock):
        client = from_environment.return_value
        client.profiles.return_value = {"h3-standard-quality": {"enabled": True}}
        client.snapshot.return_value = {"nodes": [{"status": "ONLINE"}]}
        client.submit.return_value = {"id": 43, "status": "QUEUED"}
        payload = request("480P")
        payload["acceleration"] = "turbo"
        metadata = scheduler_quality_metadata(payload)
        task_id = submit_scheduler_job(
            payload, metadata, "aid-h3-quality-43", "h3-standard-quality", "H3 Quality"
        )
        self.assertEqual("gpu-scheduler-43", task_id)
        self.assertEqual("h3-standard-quality", client.submit.call_args.args[0])
        self.assertEqual("h3-standard-quality", save_task_mock.call_args.args[1]["scheduler_profile"])

    @patch("comfy_h3_adapter.FAST_VSA_ENABLED", True)
    @patch("comfy_h3_adapter.GPU_SCHEDULER_REQUIRED", True)
    @patch("comfy_h3_adapter.FAST_VSA_SCHEDULER_ENABLED", True)
    @patch("comfy_h3_adapter.GpuSchedulerClient.from_environment")
    @patch("comfy_h3_adapter.http_json", side_effect=AdapterError(503, "ComfyUI unavailable"))
    def test_health_stays_available_when_scheduler_is_online_and_direct_comfy_is_down(
        self, _http_json, from_environment
    ):
        client = from_environment.return_value
        client.profiles.return_value = {"h3-single-fast": {"enabled": True}}
        client.snapshot.return_value = {"nodes": [{"status": "ONLINE"}]}

        response = health()

        self.assertTrue(response["ok"])
        self.assertFalse(response["comfy_available"])
        self.assertTrue(response["gpu_scheduler"]["available"])
        self.assertTrue(response["acceleration_profiles"]["fast_vsa"])
        self.assertFalse(response["acceleration_profiles"]["stable"])
        _http_json.assert_not_called()

    @patch("comfy_h3_adapter.save_task")
    @patch("comfy_h3_adapter.GpuSchedulerClient.from_environment")
    def test_fast_vsa_scheduler_submission_uses_profile_and_normalized_request(
        self, from_environment, save_task_mock
    ):
        client = from_environment.return_value
        client.profiles.return_value = {"h3-single-fast": {"enabled": True}}
        client.snapshot.return_value = {"nodes": [{"status": "ONLINE"}]}
        client.submit.return_value = {"id": 42, "status": "QUEUED"}
        payload = request("768P")
        payload["acceleration"] = "fast_vsa"
        with patch("comfy_h3_adapter.FAST_VSA_ENABLED", True):
            _graph, metadata = build_workflow(payload)

        task_id = submit_fast_vsa_scheduler(payload, metadata, "aid-h3-123")

        self.assertEqual("gpu-scheduler-42", task_id)
        profile, job_payload, idempotency = client.submit.call_args.args
        self.assertEqual("h3-single-fast", profile)
        self.assertEqual("aid-h3-123", idempotency)
        self.assertEqual("480P", job_payload["videoRequest"]["resolution"])
        self.assertEqual("fast_vsa", job_payload["videoRequest"]["acceleration"])
        saved_metadata = save_task_mock.call_args.args[1]
        self.assertEqual("gpu_scheduler", saved_metadata["backend"])
        self.assertEqual(42, saved_metadata["scheduler_job_id"])

    @patch("comfy_h3_adapter.GpuSchedulerClient.from_environment")
    def test_fast_vsa_scheduler_query_maps_success_without_touching_comfy(self, from_environment):
        from_environment.return_value.get_job.return_value = {
            "id": 42,
            "status": "SUCCEEDED",
            "result": {"resultRef": "http://10.190.0.222:8191/view/out.mp4"},
        }

        response = scheduler_task_response("gpu-scheduler-42", {
            "backend": "gpu_scheduler",
            "scheduler_job_id": 42,
            "duration": 5,
            "resolution": "480P",
        })

        self.assertEqual("succeeded", response["task"]["status"])
        self.assertEqual("http://10.190.0.222:8191/view/out.mp4", response["task"]["content"]["url"])
        self.assertEqual(5, response["task"]["usage"]["output_seconds"])

    @patch("comfy_h3_adapter.GpuSchedulerClient.from_environment")
    def test_quality_scheduler_query_preserves_acceleration_and_reference_usage(self, from_environment):
        from_environment.return_value.get_job.return_value = {
            "id": 43, "status": "SUCCEEDED",
            "result": {"resultRef": "http://example.test/out.mp4", "acceleration": "turbo",
                       "inputImageCount": 2, "inputSeconds": 3},
        }
        response = scheduler_task_response("gpu-scheduler-43", {
            "backend": "gpu_scheduler", "scheduler_job_id": 43,
            "duration": 4, "resolution": "480P", "acceleration": "turbo",
        })
        self.assertEqual("turbo", response["task"]["acceleration"])
        self.assertEqual(2, response["task"]["usage"]["input_image_count"])
        self.assertEqual(3, response["task"]["usage"]["input_seconds"])

    @patch("comfy_h3_adapter.time.time", return_value=10_000)
    @patch("comfy_h3_adapter.http_json")
    @patch("comfy_h3_adapter.load_tasks")
    def test_long_running_task_is_not_lost_during_history_commit_gap(
        self, load_tasks_mock, http_json_mock, _time_mock
    ):
        load_tasks_mock.return_value = {
            "long-task": {
                "created_at": 10_000 - 32 * 60,
                "comfy_url": "http://comfy.example",
            }
        }
        http_json_mock.side_effect = [{}, {"queue_running": [], "queue_pending": []}]

        response = task_response("long-task")

        self.assertEqual("queued", response["task"]["status"])

    def test_submit_timeout_can_recover_already_queued_prompt(self):
        graph, _metadata = build_workflow(request("480P"))
        marker = attach_submission_marker(graph)
        queue = {
            "queue_running": [],
            "queue_pending": [[7, "accepted-task-id", graph, {"client_id": "aid-studio-local"}]],
        }

        self.assertEqual("accepted-task-id", queued_prompt_id(queue, marker))

    @patch("comfy_h3_adapter.release_comfy_models")
    @patch("comfy_h3_adapter.KEEP_WARM_SECONDS", 0)
    def test_zero_warm_window_releases_runtime_immediately(self, release):
        schedule_comfy_release("http://comfy.example/")

        release.assert_called_once_with("http://comfy.example")

    @patch("comfy_h3_adapter.release_comfy_models")
    @patch("comfy_h3_adapter.http_json", return_value={"queue_running": [], "queue_pending": []})
    def test_idle_runtime_release_checks_queue_first(self, http_json_mock, release):
        _release_comfy_if_idle("http://comfy.example")

        http_json_mock.assert_called_once_with("http://comfy.example/queue", timeout=15)
        release.assert_called_once_with("http://comfy.example")

    @patch("comfy_h3_adapter.RESOURCE_RELEASE_MIN_INTERVAL", 0)
    @patch("comfy_h3_adapter.RESOURCE_RELEASE_URLS", ("http://peer.example/release",))
    @patch("comfy_h3_adapter.RESOURCE_RELEASE_ENABLED", True)
    @patch("comfy_h3_adapter.urllib.request.urlopen")
    def test_peer_release_is_best_effort(self, urlopen):
        urlopen.return_value.__enter__.return_value.read.return_value = b"{}"

        release_peer_gpu_models()

        request_arg = urlopen.call_args.args[0]
        self.assertEqual("http://peer.example/release", request_arg.full_url)
        self.assertEqual("POST", request_arg.method)

    def test_480p_profile_uses_gpu_tiled_vae(self):
        graph, metadata = build_workflow(request("480P"))

        self.assertEqual((832, 480), (graph["6"]["inputs"]["width"], graph["6"]["inputs"]["height"]))
        self.assertEqual("VAELoaderKJ", graph["4"]["class_type"])
        self.assertEqual("main_device", graph["4"]["inputs"]["device"])
        self.assertEqual("fp16", graph["4"]["inputs"]["weight_dtype"])
        self.assertEqual("VAEDecodeTiled", graph["12"]["class_type"])
        self.assertEqual(["13", 0], graph["12"]["inputs"]["samples"])
        self.assertEqual(16, graph["12"]["inputs"]["temporal_size"])
        self.assertEqual("LTXVSeparateAVLatent", graph["13"]["class_type"])
        self.assertEqual(["11", 0], graph["13"]["inputs"]["av_latent"])
        self.assertEqual("VAELoaderKJ", graph["16"]["class_type"])
        self.assertEqual("minimax_h3_audio_vae_fp32.safetensors", graph["16"]["inputs"]["vae_name"])
        self.assertEqual("cpu", graph["16"]["inputs"]["device"])
        self.assertEqual("VAEDecodeAudio", graph["17"]["class_type"])
        self.assertEqual(["13", 1], graph["17"]["inputs"]["samples"])
        self.assertEqual(["17", 0], graph["14"]["inputs"]["audio"])
        self.assertEqual("480P", metadata["resolution"])
        self.assertEqual("gpu_tiled", metadata["vae_decode"])
        self.assertTrue(metadata["native_audio"])

    def test_768p_profile_keeps_production_canvas(self):
        graph, metadata = build_workflow(request("768P"))

        self.assertEqual((1344, 768), (graph["6"]["inputs"]["width"], graph["6"]["inputs"]["height"]))
        self.assertEqual((1344, 768), (metadata["width"], metadata["height"]))

    def test_turbo_profile_applies_matching_lora_schedule(self):
        payload = request("768P")
        payload["acceleration"] = "turbo"

        graph, metadata = build_workflow(payload)

        self.assertEqual("LoraLoaderModelOnly", graph["90"]["class_type"])
        self.assertIn("fl2v_turbo_8step", graph["90"]["inputs"]["lora_name"])
        self.assertEqual(["90", 0], graph["2"]["inputs"]["model"])
        self.assertEqual(6.0, graph["2"]["inputs"]["shift_video"])
        self.assertEqual("euler", graph["8"]["inputs"]["sampler_name"])
        self.assertEqual(8, graph["10"]["inputs"]["steps"])
        self.assertEqual("turbo", metadata["acceleration"])

    @patch("comfy_h3_adapter.FAST_VSA_ENABLED", True)
    def test_fast_vsa_profile_uses_distilled_sparse_workflow(self):
        payload = request("768P")
        payload["acceleration"] = "fast_vsa"

        graph, metadata = build_workflow(payload)

        self.assertEqual("BlockSparseAttention", graph["4"]["class_type"])
        self.assertEqual("vsa", graph["4"]["inputs"]["selection"])
        self.assertEqual(10.0, graph["4"]["inputs"]["selection.keep_percent"])
        self.assertEqual(8192, graph["4"]["inputs"]["min_tokens"])
        self.assertEqual(8, graph["12"]["inputs"]["steps"])
        self.assertEqual((832, 480), (metadata["width"], metadata["height"]))
        self.assertEqual("fast_vsa", metadata["acceleration"])

    @patch("comfy_h3_adapter.KEEP_WARM_SECONDS", 3600)
    @patch("comfy_h3_adapter.FAST_VSA_KEEP_WARM_SECONDS", 21600)
    def test_fast_vsa_worker_uses_extended_warm_window(self):
        self.assertEqual(21600, keep_warm_seconds_for({"acceleration": "fast_vsa"}))
        self.assertEqual(3600, keep_warm_seconds_for({"acceleration": "turbo"}))

    @patch("comfy_h3_adapter.VEDA_ENABLED", True)
    def test_fast_veda_profile_is_limited_to_480p_text_to_video(self):
        payload = request("768P")
        payload["acceleration"] = "fast_veda"

        graph, metadata = build_workflow(payload)

        self.assertEqual("VedaSparseAttention", graph["4"]["class_type"])
        self.assertEqual("90%", graph["4"]["inputs"]["generated_sparsity"])
        self.assertEqual("LoraLoaderModelOnly", graph["2"]["class_type"])
        self.assertEqual(8, graph["12"]["inputs"]["steps"])
        self.assertEqual((832, 480), (metadata["width"], metadata["height"]))
        self.assertEqual("480P", metadata["resolution"])
        self.assertEqual("768P", metadata["requested_resolution"])
        self.assertEqual("fast_veda", metadata["acceleration"])

    @patch("comfy_h3_adapter.VEDA_ENABLED", True)
    def test_fast_veda_rejects_reference_media(self):
        payload = request("480P")
        payload["acceleration"] = "fast_veda"
        payload["content"].append({
            "type": "image_url",
            "role": "reference_image",
            "image_url": {"url": "http://127.0.0.1/reference.png"},
        })

        with self.assertRaisesRegex(AdapterError, "only supports text-to-video"):
            build_workflow(payload)

    def test_long_768p_request_is_rejected_instead_of_silent_downscale(self):
        payload = request("768P")
        payload["duration"] = 10

        with self.assertRaisesRegex(AdapterError, "768P 单段最多 5 秒"):
            build_workflow(payload)

    @patch("comfy_h3_adapter.LONG_SHOT_POLICY", "cap")
    def test_fourteen_second_request_is_capped_to_safe_duration(self):
        # Duration capping is independent from the 768P five-second hardware gate.
        payload = request("480P")
        payload["duration"] = 14

        graph, metadata = build_workflow(payload)

        self.assertEqual(10, metadata["duration"])
        self.assertEqual(14, metadata["requested_duration"])
        self.assertTrue(metadata["duration_capped"])
        self.assertEqual(243, graph["6"]["inputs"]["length"])
        self.assertIn("在10秒内压缩完成", graph["6"]["inputs"]["prompt"])

    def test_unknown_resolution_is_rejected(self):
        with self.assertRaises(AdapterError):
            build_workflow(request("360P"))

    def test_gpu_oom_is_returned_as_safe_actionable_error(self):
        error = workflow_error({"status": {"messages": [["execution_error", {
            "node_type": "SamplerCustomAdvanced",
            "exception_type": "torch.OutOfMemoryError",
            "exception_message": "Allocation on device. This error means you ran out of memory on your GPU.",
        }]]}})

        self.assertEqual("GPU_OUT_OF_MEMORY", error["code"])
        self.assertIn("10秒以内", error["message"])

    @patch("comfy_h3_adapter.upload_image", return_value=("aid-reference.png", (1024, 1024)))
    @patch("comfy_h3_adapter.download_media", return_value=(b"image", ".png", "image/png"))
    def test_single_reference_image_is_used_as_first_frame(self, _download, _upload):
        payload = request("768P")
        payload["content"].append({
            "type": "image_url",
            "role": "reference_image",
            "image_url": {"url": "http://127.0.0.1/reference.png"},
        })

        graph, metadata = build_workflow(payload)

        self.assertEqual(["18", 0], graph["6"]["inputs"]["first_frame"])
        self.assertEqual("aid-reference.png", graph["18"]["inputs"]["image"])
        self.assertEqual(1, metadata["input_image_count"])

    @patch("comfy_h3_adapter.upload_image", return_value=("aid-reference.png", (1024, 1024)))
    @patch("comfy_h3_adapter.download_media", return_value=(b"image", ".png", "image/png"))
    def test_multiple_reference_images_use_comfy_039_stock_node(self, _download, _upload):
        payload = request("768P")
        for index in range(3):
            payload["content"].append({
                "type": "image_url",
                "role": "reference_image",
                "image_url": {"url": f"http://127.0.0.1/reference-{index}.png"},
            })

        graph, metadata = build_workflow(payload)

        self.assertEqual("MiniMaxH3ReferenceToVideo", graph["6"]["class_type"])
        self.assertEqual(1344, graph["6"]["inputs"]["width"])
        self.assertEqual(768, graph["6"]["inputs"]["height"])
        self.assertEqual(90, graph["6"]["inputs"]["length"])
        self.assertEqual(["18", 0], graph["6"]["inputs"]["ref_images.ref_image_0"])
        self.assertEqual(["19", 0], graph["6"]["inputs"]["ref_images.ref_image_1"])
        self.assertEqual(["20", 0], graph["6"]["inputs"]["ref_images.ref_image_2"])
        self.assertEqual(3, metadata["input_image_count"])
        self.assertTrue(metadata["reference_mode"])

    @patch("comfy_h3_adapter.FAST_VSA_ENABLED", True)
    def test_fast_vsa_rejects_reference_media(self):
        payload = request("480P")
        payload["acceleration"] = "fast_vsa"
        payload["content"].append({
            "type": "image_url",
            "role": "reference_image",
            "image_url": {"url": "http://127.0.0.1/reference.png"},
        })

        with self.assertRaisesRegex(AdapterError, "only supports text-to-video"):
            build_workflow(payload)

    @patch(
        "comfy_h3_adapter.prepare_reference_video",
        return_value=(b"prepared", ".mp4", "video/mp4", 4.0),
    )
    @patch("comfy_h3_adapter.upload_media", return_value="aid-reference.mp4")
    @patch("comfy_h3_adapter.download_media", return_value=(b"video", ".mp4", "video/mp4"))
    def test_reference_video_uses_native_h3_reference_workflow(self, _download, _upload, _prepare):
        payload = request("480P")
        payload["content"][0]["text"] = "Use <Video 1> as the action reference."
        payload["content"].append({
            "type": "video_url",
            "role": "reference_video",
            "video_url": {"url": "http://127.0.0.1/reference.mp4"},
        })

        graph, metadata = build_workflow(payload)

        self.assertEqual("minimax_h3_ref2va_pruned_int8_convrot.safetensors", graph["1"]["inputs"]["unet_name"])
        self.assertEqual("MiniMaxH3ReferenceToVideo", graph["6"]["class_type"])
        self.assertEqual(["19", 0], graph["6"]["inputs"]["ref_videos.ref_video_0"])
        self.assertEqual(["19", 1], graph["6"]["inputs"]["ref_video_audios.ref_video_audio_0"])
        self.assertEqual("LoadVideo", graph["18"]["class_type"])
        self.assertEqual("GetVideoComponents", graph["19"]["class_type"])
        self.assertEqual(4, metadata["input_video_seconds"])
        self.assertTrue(metadata["reference_mode"])

    def test_reference_audio_without_visual_reference_is_rejected(self):
        payload = request("480P")
        payload["content"].append({
            "type": "audio_url",
            "role": "reference_audio",
            "audio_url": {"url": "http://127.0.0.1/reference.mp3"},
        })

        with self.assertRaises(AdapterError):
            build_workflow(payload)

    @patch(
        "comfy_h3_adapter.prepare_reference_video",
        return_value=(b"prepared", ".mp4", "video/mp4", 4.0),
    )
    @patch("comfy_h3_adapter.upload_media", return_value="aid-target.mp4")
    @patch("comfy_h3_adapter.upload_image", return_value=("aid-face.png", (768, 1024)))
    @patch(
        "comfy_h3_adapter.download_media",
        side_effect=[
            (b"image", ".png", "image/png"),
            (b"video", ".mp4", "video/mp4"),
        ],
    )
    def test_face_swap_preserves_target_video_audio(
        self, _download, _upload_image, _upload_media, _prepare
    ):
        payload = request("480P")
        payload["operation"] = "face_swap"
        payload["content"].extend([
            {
                "type": "image_url",
                "role": "reference_image",
                "image_url": {"url": "http://127.0.0.1/face.png"},
            },
            {
                "type": "video_url",
                "role": "reference_video",
                "video_url": {"url": "http://127.0.0.1/target.mp4"},
            },
        ])

        graph, metadata = build_workflow(payload)

        self.assertEqual("ReActorFaceSwap", graph["4"]["class_type"])
        self.assertEqual(["2", 0], graph["4"]["inputs"]["input_image"])
        self.assertEqual(["3", 0], graph["4"]["inputs"]["source_image"])
        self.assertEqual(["2", 1], graph["5"]["inputs"]["audio"])
        self.assertEqual("face_swap", metadata["operation"])
        self.assertEqual(4, metadata["input_video_seconds"])

    def test_face_swap_requires_one_image_and_one_video(self):
        payload = request("480P")
        payload["operation"] = "face_swap"
        payload["content"].append({
            "type": "video_url",
            "role": "reference_video",
            "video_url": {"url": "http://127.0.0.1/target.mp4"},
        })

        with self.assertRaisesRegex(AdapterError, "exactly one source face image"):
            build_workflow(payload)

    def test_local_fixed_port_reference_limits_are_enforced(self):
        payload = request("480P")
        for index in range(2):
            payload["content"].append({
                "type": "video_url",
                "role": "reference_video",
                "video_url": {"url": f"http://127.0.0.1/reference-{index}.mp4"},
            })

        with self.assertRaisesRegex(AdapterError, "local limit of 1"):
            build_workflow(payload)


if __name__ == "__main__":
    unittest.main()
