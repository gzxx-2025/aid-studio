# Public provider integration guide

This guide describes where a public model provider belongs in AID Studio. Keep
provider-specific protocol details at the adapter boundary and reuse the common
task, billing, callback, retry, and compensation flow.

## Choose the capability entry point

Declare the provider in the existing capability registry for its modality:

- text providers expose text input and text output;
- image providers declare supported reference-image count, resolution, and ratio;
- video providers declare duration, ratio, and reference-image limits;
- voice providers declare input format, output format, and duration limits.

The adapter should translate the provider response into the common task result.
It must not create a parallel task table or a second billing path.

## Required adapter behavior

1. Validate capability and input limits before submitting a remote task.
2. Use the shared task creation, status callback, retry, and failure-compensation
   services.
3. Preserve the provider request identifier for callback correlation.
4. Map provider errors to the common failure categories and redact credentials
   and private prompts from logs.
5. Document the provider's public API, supported models, limits, and public
   pricing with links to the vendor's official documentation.

## Local validation checklist

- Test a successful request and a provider rejection.
- Test timeout and retry behavior without charging twice.
- Verify callback correlation and terminal failure compensation.
- Check that logs contain no keys, proxy addresses, private prompts, or account
  details.
- Run the relevant unit tests and explain any unavailable external-provider test
  in the pull request.
