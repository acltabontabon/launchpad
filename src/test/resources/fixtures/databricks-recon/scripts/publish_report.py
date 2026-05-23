"""Publish daily recon summary to the metrics topic.

Plain Python script (not a Databricks notebook) - runs as a job task on a
job cluster. Reads the materialised diff table, computes summary stats,
writes to the metrics output table, and emits an MLflow run for tracking.
"""

import os
import sys
from datetime import date

from pyspark.sql import SparkSession, functions as F


def main(recon_date: str, target_catalog: str) -> None:
    spark = SparkSession.builder.appName("orders-recon-publish").getOrCreate()

    diff = spark.read.table(f"{target_catalog}.recon.orders_recon_diff")

    summary = diff.agg(
        F.count("*").alias("discrepancy_count"),
        F.sum(F.abs("amount_diff")).alias("total_abs_diff"),
        F.countDistinct("order_id").alias("affected_orders"),
    ).withColumn("recon_date", F.lit(recon_date))

    (
        summary.write
        .mode("overwrite")
        .option("partitionOverwriteMode", "dynamic")
        .partitionBy("recon_date")
        .saveAsTable(f"{target_catalog}.recon.orders_recon_summary")
    )

    spark.stop()


if __name__ == "__main__":
    main(
        recon_date=os.environ.get("RECON_DATE", date.today().isoformat()),
        target_catalog=sys.argv[1] if len(sys.argv) > 1 else "main",
    )
