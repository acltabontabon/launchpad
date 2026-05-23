-- Right side: warehouse snapshot for the recon date.
-- Parameter: ${recon_date}
SELECT
    order_id,
    amount,
    settled_at
FROM ${catalog}.warehouse.orders_daily
WHERE snapshot_date = DATE('${recon_date}')
