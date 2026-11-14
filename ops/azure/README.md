# Azure subscription configuration (AIO, 14 Nov 2026)

Subscription `150909ba-4c23-44e9-b98d-e8a2d433f709` ("Azure subscription 1"), tenant
`e1ddabd7-…`, owner `rishabh@scalesafe.co.in`. AKS `creative` in resource group `creative`,
South India. Apply JSON bodies with `az rest --method put --url <ARM id>?api-version=… --body @file`
(Git Bash: `export MSYS_NO_PATHCONV=1`; az.cmd breaks on `{}`/`&` in arguments).

| Item | Definition | ARM |
| --- | --- | --- |
| Budget USD 50/month, emails at 50/80/100% actual + 100% forecast | `budget-aio-monthly-usd50.json` | `Microsoft.Consumption/budgets/aio-monthly-usd50` (2023-05-01) |
| Action group (email rishabh@scalesafe.co.in) | `alerts/action-group-ag-aio-ops.json` | `Microsoft.Insights/actionGroups/ag-aio-ops` (2023-01-01) |
| 7 metric alerts (free AKS platform metrics + LB probe) | `alerts/aio-*.json` | `Microsoft.Insights/metricAlerts/<name>` (2018-03-01) |
| AKS maintenance windows | Sunday 02:00 IST, 4 h, for `aksManagedAutoUpgradeSchedule` and `aksManagedNodeOSUpgradeSchedule` | `az aks maintenanceconfiguration update` |
| Backup storage `aiocreativebackups` (GRS, Cool, no public/shared-key, TLS1.2, 14-day soft delete) | lifecycle `storage-lifecycle-postgres-backups.json` | see docs/AZURE-DATABASE-BACKUP.md |
| Managed identity `id-aio-postgres-backup` | Storage Blob Data Contributor on container `postgres-backups` only; federated to `system:serviceaccount:aio-validation:postgres-backup` | |

Resource providers registered: Microsoft.Storage, Insights, OperationalInsights, AlertsManagement.

Not done (cost): Application Insights HTTPS availability tests (~USD 80/month for 5 sites at
5 min x 3 locations). Not done (lockout risk): API server authorized IP ranges.
Unrelated resource in this resource group: `rishabh-5619-resource` (AIServices S0, westus3).
