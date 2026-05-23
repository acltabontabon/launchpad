# orders-recon

Daily reconciliation pipeline. Compares orders from the upstream OLTP CDC stream
against the downstream warehouse snapshot and emits a discrepancy table plus a
summary metric for monitoring.

Deployed to Databricks via Terraform. Source is a mix of Python (DLT) and SQL.
