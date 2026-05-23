-- Left side: OLTP orders aggregated to the recon grain.
-- Parameter: ${recon_date}
SELECT
    order_id,
    SUM(amount)   AS amount,
    COUNT(*)      AS event_count,
    MAX(event_ts) AS last_event_ts
FROM ${catalog}.raw.orders_cdc
WHERE event_date = DATE('${recon_date}')
GROUP BY order_id
