package com.acltabontabon.launchpad.model;

/**
 * Classification of a discovered workflow by how it is triggered. Drives
 * standards scope matching and task seeding downstream.
 */
public enum WorkflowType {
    INBOUND_API,
    SCHEDULED,
    EVENT_DRIVEN,
    BATCH,
    INTEGRATION,
    INTERNAL
}
