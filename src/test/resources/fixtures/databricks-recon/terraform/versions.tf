terraform {
  required_version = ">= 1.6"

  required_providers {
    databricks = {
      source  = "databricks/databricks"
      version = "1.41.0"
    }
    aws = {
      source  = "hashicorp/aws"
      version = "5.31.0"
    }
  }

  backend "s3" {
    bucket = "recon-tfstate"
    key    = "orders-recon/terraform.tfstate"
    region = "us-east-1"
  }
}
