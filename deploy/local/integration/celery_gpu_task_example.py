"""Example Celery bridge: submit and poll without giving the worker a GPU."""

from celery import shared_task

from gpu_scheduler_client import GpuSchedulerClient


@shared_task(bind=True, autoretry_for=(), max_retries=None)
def submit_gpu_work(self, project_task_id: str, profile: str, request_ref: str):
    client = GpuSchedulerClient.from_environment()
    job = client.submit(
        profile=profile,
        idempotency_key=f"project-task:{project_task_id}:v1",
        payload={
            "projectTaskId": project_task_id,
            # The fixed node adapter resolves this reference. Never pass a shell command.
            "requestRef": request_ref,
        },
    )
    poll_gpu_work.apply_async(args=[project_task_id, job["id"]], countdown=5)
    return {
        "schedulerJobId": job["id"],
        "status": job["status"],
        "queuePosition": job.get("queuePosition"),
        "estimatedStartSeconds": job.get("estimatedStartSeconds"),
    }


@shared_task(bind=True, max_retries=None)
def poll_gpu_work(self, project_task_id: str, scheduler_job_id: int):
    client = GpuSchedulerClient.from_environment()
    job = client.get_job(scheduler_job_id)
    status = job["status"]
    if status in {"QUEUED", "RUNNING"}:
        raise self.retry(countdown=5)
    if status != "SUCCEEDED":
        # Persist this state in the project's own task table before returning in real code.
        return {
            "projectTaskId": project_task_id,
            "schedulerJobId": scheduler_job_id,
            "status": status,
            "error": job.get("error"),
        }
    # The fixed adapter writes media/result metadata to the project's own storage or callback.
    return {
        "projectTaskId": project_task_id,
        "schedulerJobId": scheduler_job_id,
        "status": status,
    }
