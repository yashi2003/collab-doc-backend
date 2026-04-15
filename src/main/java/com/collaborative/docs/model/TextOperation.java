package com.collaborative.docs.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single atomic text operation component.
 *
 * Examples:
 *   INSERT at position 5, text "Hello"  -> {type: INSERT, position: 5, text: "Hello", length: 5}
 *   DELETE at position 3, length 2      -> {type: DELETE, position: 3, text: null, length: 2}
 *   RETAIN 10 characters                -> {type: RETAIN, position: 0, text: null, length: 10}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextOperation {

    private OperationType type;
    private int position;
    private String text;    // only for INSERT
    private int length;     // for DELETE = chars to remove, for INSERT = text.length(), for RETAIN = chars to skip
}
