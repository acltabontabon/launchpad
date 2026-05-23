variable "databricks_host" {
  description = "Databricks workspace URL"
  type        = string
}

variable "databricks_profile" {
  description = "Databricks CLI profile name for auth"
  type        = string
  default     = "DEFAULT"
}

variable "target_catalog" {
  description = "Unity Catalog catalog name for recon outputs"
  type        = string
}
