# AIO automatic database backups

Verified against live Cloud SQL instance `aio-002` in project
`project-d28840cd-be2f-4901-85e` on 2026-11-13.

- Automatic backups are enabled, with a daily backup window starting at
  05:00 UTC (10:30 AM Asia/Calcutta). Actual execution can be later in the window.
- The latest seven automated backups are retained in location `asia`.
- Point-in-time recovery is enabled, with seven days of transaction logs.
- Automated backup `1794546000000` completed successfully on 2026-11-13
  at 07:40 UTC; the previous day's automated backup also succeeded.

These are instance-level database backups. They do not back up Git repositories,
container images, Kubernetes configuration, external identity providers, or files
stored outside this Cloud SQL instance.

## Check the live configuration

```powershell
gcloud sql instances describe aio-002 --project=project-d28840cd-be2f-4901-85e --format="json(name,settings.backupConfiguration)"
gcloud sql backups list --instance=aio-002 --project=project-d28840cd-be2f-4901-85e --limit=10 --format="table(id,type,status,startTime,endTime)"
```

No application process or Kubernetes CronJob is needed for these managed backups.
Backups continue while the application is stopped.

The fresh-environment Terraform configuration in
`infra/terraform/foundation/database.tf` also enables daily backups and seven-day
point-in-time recovery, with a different default window (03:00 UTC). It does not
manage the existing `aio-002` instance; do not apply it to production as a way of
updating backups.

For recovery, restore to a separate instance first and validate the data before
switching the application. Follow `docs/architecture/MIGRATION.md`; the deployment
tracker records a previous successful restore rehearsal.
