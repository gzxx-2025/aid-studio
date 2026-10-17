"""ComfyUI benchmark-only passthrough that invalidates the sampler chain."""

from __future__ import annotations


class H3BenchmarkForceRun:
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"model": ("MODEL",)}}

    RETURN_TYPES = ("MODEL",)
    FUNCTION = "passthrough"
    CATEGORY = "testing/h3"

    @classmethod
    def IS_CHANGED(cls, **_kwargs):
        return float("nan")

    def passthrough(self, model):
        return (model,)


NODE_CLASS_MAPPINGS = {"H3BenchmarkForceRun": H3BenchmarkForceRun}
NODE_DISPLAY_NAME_MAPPINGS = {"H3BenchmarkForceRun": "H3 Benchmark Force Run"}
