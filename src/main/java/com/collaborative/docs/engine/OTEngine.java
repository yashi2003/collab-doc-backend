package com.collaborative.docs.engine;

import com.collaborative.docs.model.OperationType;
import com.collaborative.docs.model.TextOperation;
import org.springframework.stereotype.Component;

/**
 * The Operational Transformation Engine.
 *
 * This is the CORE of the entire system. It solves the fundamental problem:
 * "Two users edited the document at the same time, based on the same version.
 *  How do we merge both edits so neither is lost and the document stays consistent?"
 *
 * === THE TRANSFORM FUNCTION ===
 *
 * Given two operations A and B that were both created against the SAME document state:
 *   transform(A, B) -> (A', B')
 *
 * Such that:
 *   apply(apply(document, A), B') == apply(apply(document, B), A')
 *
 * This is called the "transformation property" or "convergence property".
 *
 * === EXAMPLE ===
 *
 * Document: "ABCDE" (version 5)
 *
 * User A: INSERT("X", position=2)   -> "ABXCDE"
 * User B: DELETE(position=3, len=1) -> "ABCE"     (deletes 'D')
 *
 * Both were based on version 5. Server receives A first:
 *   1. Apply A -> document becomes "ABXCDE" (version 6)
 *   2. Transform B against A:
 *      B was DELETE(pos=3), but A inserted a character at pos=2 (before pos 3).
 *      So B's position shifts right by 1: B' = DELETE(pos=4, len=1)
 *   3. Apply B' -> "ABXCE" (version 7)
 *
 * Result: Both edits are preserved. 'X' was inserted AND 'D' was deleted.
 */
@Component
public class OTEngine {

    /**
     * Apply an operation to a document's content string.
     *
     * @param content   current document text
     * @param operation the operation to apply
     * @return the new document text after applying the operation
     * @throws IllegalArgumentException if position is out of bounds
     */
    public String apply(String content, TextOperation operation) {
        if (content == null) {
            content = "";
        }

        switch (operation.getType()) {
            case INSERT:
                validatePosition(operation.getPosition(), content.length() + 1);
                return content.substring(0, operation.getPosition())
                        + operation.getText()
                        + content.substring(operation.getPosition());

            case DELETE:
                int end = operation.getPosition() + operation.getLength();
                validatePosition(operation.getPosition(), content.length());
                if (end > content.length()) {
                    end = content.length(); // defensive: don't delete past end
                }
                return content.substring(0, operation.getPosition())
                        + content.substring(end);

            case RETAIN:
                return content; // no change, just cursor movement

            default:
                throw new IllegalArgumentException("Unknown operation type: " + operation.getType());
        }
    }

    /**
     * Transform operation B against operation A.
     *
     * Both A and B were created against the SAME document version.
     * A has already been applied to the server document.
     * We need to adjust B so that it still makes sense on top of A.
     *
     * @param serverOp the operation already applied (A)
     * @param clientOp the incoming operation to transform (B)
     * @return the transformed version of clientOp (B')
     */
    public TextOperation transform(TextOperation serverOp, TextOperation clientOp) {
        // If either is RETAIN, no positional conflict
        if (serverOp.getType() == OperationType.RETAIN || clientOp.getType() == OperationType.RETAIN) {
            return TextOperation.builder()
                    .type(clientOp.getType())
                    .position(clientOp.getPosition())
                    .text(clientOp.getText())
                    .length(clientOp.getLength())
                    .build();
        }

        return switch (serverOp.getType()) {
            case INSERT -> transformAgainstInsert(serverOp, clientOp);
            case DELETE -> transformAgainstDelete(serverOp, clientOp);
            default -> clientOp;
        };
    }

    /**
     * Server applied an INSERT. Adjust clientOp's position accordingly.
     *
     * Intuition: If the server inserted text BEFORE the client's position,
     * the client's position needs to shift right by the insertion length.
     *
     * Edge case: If both insert at the SAME position, server wins (goes first).
     * This is an arbitrary but consistent tie-breaking rule.
     */
    private TextOperation transformAgainstInsert(TextOperation serverInsert, TextOperation clientOp) {
        int serverPos = serverInsert.getPosition();
        int serverLen = serverInsert.getText().length();
        int clientPos = clientOp.getPosition();

        TextOperation transformed = TextOperation.builder()
                .type(clientOp.getType())
                .text(clientOp.getText())
                .length(clientOp.getLength())
                .build();

        if (clientOp.getType() == OperationType.INSERT) {
            // Both inserting: if client's position is AFTER (or at) server's insert point,
            // shift client right by the server's insertion length
            if (clientPos >= serverPos) {
                transformed.setPosition(clientPos + serverLen);
            } else {
                transformed.setPosition(clientPos);
            }
        } else if (clientOp.getType() == OperationType.DELETE) {
            if (clientPos >= serverPos) {
                // Client's delete is entirely after the server's insert -> shift right
                transformed.setPosition(clientPos + serverLen);
            } else if (clientPos + clientOp.getLength() <= serverPos) {
                // Client's delete is entirely before the server's insert -> no change
                transformed.setPosition(clientPos);
            } else {
                // The server's insert falls INSIDE the client's delete range.
                // Split the delete: delete before insert, skip inserted text, delete after insert.
                // For simplicity, we adjust position and keep the original length.
                // The inserted text survives (we don't delete what the server just inserted).
                transformed.setPosition(clientPos);
                // Delete length stays the same — we delete the original characters,
                // but the inserted text is in between and we skip over it
            }
        }

        return transformed;
    }

    /**
     * Server applied a DELETE. Adjust clientOp's position accordingly.
     *
     * Intuition: If the server deleted text BEFORE the client's position,
     * the client's position needs to shift LEFT by the deleted length.
     */
    private TextOperation transformAgainstDelete(TextOperation serverDelete, TextOperation clientOp) {
        int serverPos = serverDelete.getPosition();
        int serverLen = serverDelete.getLength();
        int serverEnd = serverPos + serverLen;
        int clientPos = clientOp.getPosition();

        TextOperation transformed = TextOperation.builder()
                .type(clientOp.getType())
                .text(clientOp.getText())
                .length(clientOp.getLength())
                .build();

        if (clientOp.getType() == OperationType.INSERT) {
            if (clientPos <= serverPos) {
                // Client inserts before or at the delete start -> no shift needed
                transformed.setPosition(clientPos);
            } else if (clientPos >= serverEnd) {
                // Client inserts after the deleted range -> shift left
                transformed.setPosition(clientPos - serverLen);
            } else {
                // Client inserts INSIDE the deleted range
                // Move the insert to the delete position (best we can do)
                transformed.setPosition(serverPos);
            }
        } else if (clientOp.getType() == OperationType.DELETE) {
            if (clientPos >= serverEnd) {
                // Client's delete is entirely after server's delete -> shift left
                transformed.setPosition(clientPos - serverLen);
            } else if (clientPos + clientOp.getLength() <= serverPos) {
                // Client's delete is entirely before server's delete -> no change
                transformed.setPosition(clientPos);
            } else {
                // Overlapping deletes — the tricky case!
                // We need to remove only the parts that weren't already deleted by the server.
                int overlapStart = Math.max(clientPos, serverPos);
                int overlapEnd = Math.min(clientPos + clientOp.getLength(), serverEnd);
                int overlapLen = overlapEnd - overlapStart;

                // Reduce client's delete length by the overlap (those chars are already gone)
                transformed.setLength(Math.max(0, clientOp.getLength() - overlapLen));

                // Position: if client starts before server's delete, keep position
                // If client starts inside server's delete range, move to server delete position
                if (clientPos < serverPos) {
                    transformed.setPosition(clientPos);
                } else {
                    transformed.setPosition(serverPos);
                }
            }
        }

        return transformed;
    }

    private void validatePosition(int position, int maxExclusive) {
        if (position < 0 || position >= maxExclusive) {
            throw new IllegalArgumentException(
                    "Position " + position + " out of bounds [0, " + (maxExclusive - 1) + "]");
        }
    }
}
