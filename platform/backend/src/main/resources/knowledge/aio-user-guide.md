# AIO user guide

Curated knowledge for the "Ask AIO" assistant. Everything in this folder may be sent, after redaction, to the
configured AI provider. Never put secrets, customer data, IP addresses or internal hostnames here.

## What AIO is

AIO is the delivery platform: it builds a project's container image (CI), records it in the GitOps repository and
lets Argo CD deploy it to the dev, staging and prod environments (CD). Each project has one values file per
environment in the GitOps repository; the image tag in that file is what runs.

## Create a project

Open **New project**, give the Git repository URL, the Dockerfile path and the build context. Templates on the
**Templates** page start a new repository from a ready-made app. AIO creates the three environments, the Argo CD
applications and, when the public URL is configured, the GitHub webhook that starts builds on push.

## Builds (CI)

A push or a pull request to the repository starts a build through the GitHub webhook. The **CI** page lists runs;
a run page shows the steps, logs and the security scan. A GitHub redelivery or a second webhook for a commit that is
already building does not start another build. A run that never starts is failed after 10 minutes.

## Promote to staging or prod

From a project page choose **Promote**. Dev to staging is applied at once. Anything that touches prod (a promotion or
a rollback) becomes a request on the **Approvals** page that an approver must approve. While it is carried out the
request shows **Applying**; it becomes **Done** only when Argo CD reports the app Synced and Healthy, running the new
image, and the smoke test against the public URL passed.

## Approvals and the four-eyes rule

With no approvers configured, anyone except the requester may approve a prod request. With `APPROVERS` set, only
the listed people may approve, and an approver approving their own request is recorded as a self-approval in the
audit log. Two people approving at once cannot both deploy: only one approval wins.

## When a deployment fails

By default a failed deployment rolls back to the previous version automatically: the previous image tag is restored in
GitOps and, for prod, synced. For environments listed in `AIO_PROMOTION_MANUAL_ENVS` a failed deployment instead
**pauses** with the status **Needs decision**. On the Approvals page, open **Show execution** and choose:

- **Retry step**: run the failed step again (for example after fixing a config value).
- **Skip step**: treat the step as passed, only after checking the app yourself.
- **Roll back**: restore the previous version now.

If nobody decides within the intervention timeout (2 hours by default) the deployment rolls back on its own. If the
automatic rollback itself fails, the request shows "needs manual attention": fix it by hand, then use **Retry** or
**Mark resolved**.

## Smoke test after deploy

After Argo CD reports Healthy, AIO calls the environment's public URL a few times. Answers below 500 count as good
(a login redirect or 401 means the app is up). Four good answers pass; two bad answers (5xx, timeout, connection
refused) fail the deployment. If an app's home page legitimately returns an error, an admin sets
`AIO_PROMOTION_SMOKE_PATH` to a health path such as `/healthz`. Environments without a public URL skip the test.

## Deployment freeze windows

During a freeze, promotions and rollbacks are not applied, and prod sync and prod values edits are refused. Requests
can still be raised; approve them after the freeze. The Approvals page shows a banner while a freeze is in force.
An AIO admin can override the freeze for one approval; the override is audited. Windows are configured by an admin in
`AIO_FREEZE_WINDOWS`, for example `prod@FRI 18:00/MON 09:00#weekend`.

## Why AIO is slow to open in the morning

To save cost AIO, Argo CD and Tekton scale to zero after 30 minutes without use and stop every night at 23:00 India
time. The first visit wakes them; the page shows a waiting screen for one to three minutes. GitHub webhooks that arrive
while AIO sleeps are buffered and replayed when it wakes, so no push is lost. While a build or a deployment is running
AIO keeps itself awake, even with the browser closed. Browser tabs stop polling when hidden or after 15 minutes
without input.

## Delivery metrics

The **Metrics** page shows DORA-style numbers: deploy frequency, lead time from build to prod, change failure rate
(prod rollbacks divided by prod deployments) and time to restore.

## What the assistant can and cannot do

The assistant answers questions from these curated documents and cites them. It cannot see your code, logs or secrets,
and it cannot change anything: every action is done by a person on the AIO page it names. It may be wrong; check the
cited source before acting, especially for prod.
