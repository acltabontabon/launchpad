provider "databricks" {
  host    = var.databricks_host
  profile = var.databricks_profile
}

resource "databricks_pipeline" "orders_recon" {
  name    = "orders-recon-dlt"
  catalog = var.target_catalog
  target  = "recon"

  library {
    notebook {
      path = databricks_notebook.recon_dlt.path
    }
  }

  cluster {
    label       = "default"
    num_workers = 2
  }
}

resource "databricks_notebook" "recon_dlt" {
  path     = "/Pipelines/orders_recon/recon_dlt"
  language = "PYTHON"
  source   = "${path.module}/../pipelines/recon_dlt.py"
}

resource "databricks_job" "publish_report" {
  name = "orders-recon-publish"

  task {
    task_key = "publish"
    spark_python_task {
      python_file = "../scripts/publish_report.py"
    }
    job_cluster_key = "default"
  }

  job_cluster {
    job_cluster_key = "default"
    new_cluster {
      spark_version = "14.3.x-scala2.12"
      node_type_id  = "i3.xlarge"
      num_workers   = 1
    }
  }
}
