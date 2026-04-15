package com.collaborative.docs.model;

/**
 * The three fundamental operations in Operational Transformation.
 *
 * INSERT - adds text at a position
 * DELETE - removes characters starting at a position
 * RETAIN - skip over characters (cursor moves forward, no change)
 */
public enum OperationType {
    INSERT,
    DELETE,
    RETAIN
}
