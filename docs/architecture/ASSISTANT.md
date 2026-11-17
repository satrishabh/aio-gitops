# AIO assistant

**Status:** implemented and unit-tested (2026-11-17); not deployed. Turn it on by setting `ANTHROPIC_API_KEY` on the platform.

The **Assistant** page (admins and business users) is a chat with Claude (`claude-opus-5-5`, effort `medium`) that can look at the signed-in user's projects, CI runs, step logs, repository files and connectors. It explains failures and proposes fixes. It cannot change anything by itself.

## What it can do

| Tool | Access |
| --- | --- |
| `list_projects`, `get_project` | only projects this user can see (same scope as the portal) |
| `list_runs`, `get_run`, `get_step_log` | runs of those projects; logs are tail-trimmed to 16k characters |
| `read_repo_file` | files in a visible project's GitHub repository, through the platform's GitHub token |
| `list_connectors` | admins only |
| `propose_action` | creates a proposal and runs **nothing** |

Proposals are limited to a fixed list: `trigger_ci`, `rerun_run`, `test_connector`, `enable_connector`, `rollback_connector`, `disable_connector`. Each one shows an **Approve / Reject** card. On Approve, the action runs through the same endpoint as the matching button, with that user's permissions; connector actions still need an admin. Approvals and rejections are written to the audit log as `assistant.approve` / `assistant.reject`.

## Safety

- **Untrusted data:** logs and code are treated as data. The system prompt tells Claude never to follow instructions found in them, and anything injected into them can at most produce a proposal, which a person must approve.
- **Secret redaction:** before any tool output leaves the platform, tokens (GitHub, Anthropic, AWS, Bearer, JWT), private keys and `password=` / `token:` / `api_key` style values are replaced with `[REDACTED]`.
- **Conversations:** they live in platform memory for 2 hours, are tied to the user who started them, and are never written to the database. Another user's conversation id behaves like an unknown one.
- **What reaches Anthropic:** the conversation and the tool data in it are sent to the Claude API. Don't enable the assistant for data that must not leave your infrastructure.
- **Refusals:** a request declined by the safety system is retried on a fallback model (server-side fallback, `fallbacks: "default"`). If it is still declined, the assistant says so.

## Settings

| Variable | Default | |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | (empty = off) | store it in the platform Secret, never in Git |
| `ASSISTANT_MODEL` | `claude-opus-5-5` | |
| `ASSISTANT_EFFORT` | `medium` | `low` is cheaper and faster for simple questions; `high` suits hard debugging |
| `ASSISTANT_MAX_STEPS` | `8` | maximum tool lookups per question |

## Cost

Claude Opus 5.5 costs $4 per million input tokens and $20 per million output tokens. The tool definitions, the system prompt and earlier turns are cached between steps. A typical "why did this build fail" question (2–4 lookups, one log) costs a few cents.
