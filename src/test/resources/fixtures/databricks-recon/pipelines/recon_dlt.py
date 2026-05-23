# Databricks notebook source
"""Orders reconciliation DLT pipeline.

Reads the CDC stream and the warehouse snapshot, materialises both as
streaming live tables, then computes the diff into a final discrepancy table
with expectations gating the publish.
"""

import dlt
from pyspark.sql import functions as F


@dlt.table(
    name="orders_oltp_stream",
    comment="Raw CDC events from the orders OLTP source.",
)
def orders_oltp_stream():
    return (
        spark.readStream
        .format("delta")
        .table("raw.orders_cdc")
    )


@dlt.table(
    name="orders_warehouse_snapshot",
    comment="Daily snapshot from the warehouse for the recon date.",
)
def orders_warehouse_snapshot():
    return spark.read.table("warehouse.orders_daily")


@dlt.table(
    name="orders_recon_diff",
    comment="Discrepancies between OLTP CDC and warehouse snapshot.",
)
@dlt.expect_or_drop("non_null_order_id", "order_id IS NOT NULL")
@dlt.expect("amount_within_tolerance", "ABS(amount_diff) < 1000")
def orders_recon_diff():
    oltp = dlt.read("orders_oltp_stream")
    snap = dlt.read("orders_warehouse_snapshot")
    return (
        oltp.alias("o")
        .join(snap.alias("s"), "order_id", "fullouter")
        .select(
            F.coalesce("o.order_id", "s.order_id").alias("order_id"),
            (F.col("o.amount") - F.col("s.amount")).alias("amount_diff"),
            F.col("o.amount").alias("oltp_amount"),
            F.col("s.amount").alias("warehouse_amount"),
        )
        .filter("amount_diff IS NULL OR amount_diff != 0")
    )
