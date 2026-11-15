# AIO troubleshooting

Curated knowledge for the assistant's "Explain this failure". Same rule as the user guide: no secrets, no addresses.

## Build step failed in the Dockerfile

A failed build step with a non-zero exit code usually means a command in the Dockerfile failed: a dependency could not
be downloaded, a test failed, or a file named in COPY does not exist in the build context. Open the run page, read the
failed step's log from the bottom up, fix the repository and push again; the push starts a new build. Check that the
project's Dockerfile path and build context in the project settings match the repository layout.

## Vulnerability gate blocked the build

The security scan step fails the run when it finds vulnerabilities at or above the project's gate severity (for
example critical). Upgrade the base image or the affected dependency and push again. When the risk is understood and
accepted, an approver can re-run once with a written reason to accept the risk; the reason is audited.

## Image push or registry access failed

A failure while pushing or pulling the image usually means the registry connection is missing, expired or not allowed
for the repository. Check the project's delivery settings for the registry connection, ask an admin to renew the
credential, then re-run the build.

## Build stuck in pending

A run that never starts is failed by AIO after 10 minutes. Common causes are the build system being asleep or out of
capacity. Re-run it; if it happens again, ask an admin to check that the build pipeline is installed and has capacity.

## Deployment Degraded or crash-looping

When Argo CD reports the app Degraded with the new image, the pods start but fail: the app crashes on start, a health
probe fails, or a required setting or secret is missing in the environment's values. AIO rolls back after five minutes
of Degraded, or pauses for a decision in environments with manual intervention. Compare the environment's values with
the previous working version on the CD page and check the app's own startup errors.

## Deployment timed out waiting for healthy

If the app is not Synced and Healthy with the new image within the deployment timeout (20 minutes by default), the
deployment fails. Slow image pulls, a large image, too little cluster capacity or a sync error can cause this. Check
the CD page for the sync status and events, then retry the step or roll back.

## Smoke test failed after deploy

The smoke test calls the environment's public URL after Argo CD reports Healthy. Server errors (5xx), timeouts or a
refused connection count as bad samples; two fail the deployment. Check that the ingress host is right, that the app
answers on its home page or set a health path, and that the app is ready before it reports healthy.

## Automatic rollback failed

If restoring the previous version fails, the request shows "needs manual attention". Restore the previous image tag
by hand on the CD page or roll back from the project's history, then use Retry or Mark resolved on the Approvals page.
