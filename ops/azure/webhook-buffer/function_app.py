"""GitHub webhook buffer for AIO, which sleeps when idle (Sablier, ops/portable/azure-hibernation.yaml).

Traefik routes https://app.sarvaikam.com/api/webhooks/github/{id} here (ops/portable/azure-webhook-buffer.yaml).

* AIO awake  -> forward at once to the ungated route <AIO>/buffered/api/webhooks/github/{id} and return AIO's reply.
* AIO asleep -> store the delivery in Blob storage, queue it, wake AIO, answer 202 to GitHub. The queue worker
  retries every 30 s (up to 40 times) until AIO is up, then replays the delivery byte-for-byte.

No secrets live here: AIO verifies each delivery's HMAC signature (X-Hub-Signature-256) itself, so a forged
request only costs a 401 from AIO.
"""
import base64
import json
import logging
import os
import urllib.error
import urllib.request
import uuid

import azure.functions as func
from azure.core.exceptions import ResourceNotFoundError
from azure.storage.blob import BlobServiceClient

app = func.FunctionApp()

AIO = os.environ.get("AIO_BASE_URL", "https://app.sarvaikam.com").rstrip("/")
DIRECT = AIO + "/buffered"   # ungated Traefik route straight to AIO (stripPrefix /buffered)
UA = "aio-webhook-buffer/1.0"  # must not match the Sablier ignoreUserAgent pattern, so wake requests count
CONTAINER = "webhook-inbox"
QUEUE = "github-webhooks"
MAX_BODY = 10 * 1024 * 1024
HEADERS = ("content-type", "x-github-event", "x-github-delivery", "x-hub-signature-256", "x-hub-signature",
           "x-github-hook-id", "x-github-hook-installation-target-id", "x-github-hook-installation-target-type")


def _http(method, url, body=None, headers=None, timeout=5.0):
    req = urllib.request.Request(url, data=body, method=method, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(4096)
    except urllib.error.HTTPError as e:
        return e.code, e.read(4096)
    except Exception as e:  # timeout, DNS, TLS, connection reset
        logging.info("%s %s failed: %s", method, url, e)
        return 0, b""


def _awake(timeout=2.0):
    # require Spring's own health body: Sablier's waiting page also answers 200
    status, body = _http("GET", DIRECT + "/actuator/health", timeout=timeout)
    return status == 200 and b'"UP"' in body


def _wake():
    # any non-bot request on the gated route starts the whole AIO group
    _http("GET", AIO + "/", timeout=2.0)


def _forward(project, headers, body, timeout):
    return _http("POST", f"{DIRECT}/api/webhooks/github/{project}", body, headers, timeout)


def _inbox():
    return BlobServiceClient.from_connection_string(os.environ["AzureWebJobsStorage"]).get_container_client(CONTAINER)


@app.route(route="webhooks/github/{project:int}", methods=["POST"], auth_level=func.AuthLevel.ANONYMOUS)
@app.queue_output(arg_name="msg", queue_name=QUEUE, connection="AzureWebJobsStorage")
def receive(req: func.HttpRequest, msg: func.Out[str]) -> func.HttpResponse:
    project = req.route_params["project"]
    body = req.get_body()
    headers = {k: v for k in HEADERS if (v := req.headers.get(k))}
    if "x-github-event" not in headers or "x-hub-signature-256" not in headers:
        return func.HttpResponse("not a signed GitHub webhook", status_code=400)
    if len(body) > MAX_BODY:
        return func.HttpResponse("payload too large", status_code=413)

    # GitHub waits 10 s; keep health + forward under ~7 s to leave room for a cold start
    if _awake():
        status, reply = _forward(project, headers, body, timeout=5.0)
        if 0 < status < 500:
            logging.info("delivery %s forwarded directly: %s", headers.get("x-github-delivery"), status)
            return func.HttpResponse(reply, status_code=status)

    _wake()
    name = f"{project}/{headers.get('x-github-delivery') or uuid.uuid4()}.json"
    envelope = {"project": project, "headers": headers, "body": base64.b64encode(body).decode()}
    _inbox().upload_blob(name, json.dumps(envelope), overwrite=True)
    msg.set(json.dumps({"blob": name}))
    logging.info("delivery %s buffered as %s (%s, %d bytes)", headers.get("x-github-delivery"), name,
                 headers["x-github-event"], len(body))
    return func.HttpResponse("buffered: AIO is waking up and will receive this event shortly", status_code=202)


@app.queue_trigger(arg_name="m", queue_name=QUEUE, connection="AzureWebJobsStorage")
def deliver(m: func.QueueMessage) -> None:
    name = m.get_json()["blob"]
    blob = _inbox().get_blob_client(name)
    try:
        envelope = json.loads(blob.download_blob().readall())
    except ResourceNotFoundError:
        logging.warning("buffered delivery %s is gone; nothing to do", name)
        return

    if not _awake():
        _wake()
        # raising returns the message to the queue; it reappears after visibilityTimeout (host.json)
        raise RuntimeError(f"AIO not awake yet for {name} (attempt {m.dequeue_count})")

    status, reply = _forward(envelope["project"], envelope["headers"], base64.b64decode(envelope["body"]), 30.0)
    if status == 0 or status == 429 or status >= 500:
        raise RuntimeError(f"AIO answered {status} for {name} (attempt {m.dequeue_count})")
    # 2xx = handled; 4xx = AIO rejected it for good (bad signature, unknown project): do not retry
    logging.info("buffered delivery %s replayed: %s %s", name, status, reply[:200])
    blob.delete_blob()
