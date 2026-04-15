package com.collaborative.docs.engine;

import com.collaborative.docs.model.OperationType;
import com.collaborative.docs.model.TextOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the OT Engine.
 * These test the core convergence property:
 *   apply(apply(doc, A), transform(B, A)) == apply(apply(doc, B), transform(A, B))
 */
class OTEngineTest {

    private OTEngine engine;

    @BeforeEach
    void setUp() {
        engine = new OTEngine();
    }

    // ======================== APPLY TESTS ========================

    @Nested
    @DisplayName("Apply operations")
    class ApplyTests {

        @Test
        @DisplayName("Insert at beginning")
        void insertAtBeginning() {
            TextOperation op = TextOperation.builder()
                    .type(OperationType.INSERT).position(0).text("Hello").length(5).build();
            assertEquals("HelloWorld", engine.apply("World", op));
        }

        @Test
        @DisplayName("Insert at end")
        void insertAtEnd() {
            TextOperation op = TextOperation.builder()
                    .type(OperationType.INSERT).position(5).text("!").length(1).build();
            assertEquals("Hello!", engine.apply("Hello", op));
        }

        @Test
        @DisplayName("Insert in middle")
        void insertInMiddle() {
            TextOperation op = TextOperation.builder()
                    .type(OperationType.INSERT).position(2).text("XY").length(2).build();
            assertEquals("ABXYCDE", engine.apply("ABCDE", op));
        }

        @Test
        @DisplayName("Delete from beginning")
        void deleteFromBeginning() {
            TextOperation op = TextOperation.builder()
                    .type(OperationType.DELETE).position(0).length(3).build();
            assertEquals("lo", engine.apply("Hello", op));
        }

        @Test
        @DisplayName("Delete from end")
        void deleteFromEnd() {
            TextOperation op = TextOperation.builder()
                    .type(OperationType.DELETE).position(3).length(2).build();
            assertEquals("Hel", engine.apply("Hello", op));
        }

        @Test
        @DisplayName("Insert into empty document")
        void insertIntoEmpty() {
            TextOperation op = TextOperation.builder()
                    .type(OperationType.INSERT).position(0).text("Hi").length(2).build();
            assertEquals("Hi", engine.apply("", op));
        }

        @Test
        @DisplayName("Retain does not change content")
        void retainNoChange() {
            TextOperation op = TextOperation.builder()
                    .type(OperationType.RETAIN).position(0).length(5).build();
            assertEquals("Hello", engine.apply("Hello", op));
        }
    }

    // ======================== TRANSFORM TESTS ========================

    @Nested
    @DisplayName("Transform: INSERT vs INSERT")
    class InsertInsertTests {

        @Test
        @DisplayName("Server inserts before client -> client shifts right")
        void serverInsertBeforeClient() {
            // Document: "ABCDE"
            // Server: INSERT("X", pos=1) -> "AXBCDE"
            // Client: INSERT("Y", pos=3) -> should become pos=4 on transformed doc
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(1).text("X").length(1).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(3).text("Y").length(1).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            assertEquals(4, transformed.getPosition());
            assertEquals("Y", transformed.getText());
        }

        @Test
        @DisplayName("Server inserts after client -> client stays")
        void serverInsertAfterClient() {
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(5).text("X").length(1).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(2).text("Y").length(1).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            assertEquals(2, transformed.getPosition());
        }

        @Test
        @DisplayName("Both insert at same position -> server wins, client shifts")
        void samePositionServerWins() {
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(3).text("X").length(1).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(3).text("Y").length(1).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            // Server's insert goes first, so client shifts right
            assertEquals(4, transformed.getPosition());
        }
    }

    @Nested
    @DisplayName("Transform: INSERT vs DELETE")
    class InsertDeleteTests {

        @Test
        @DisplayName("Server inserts before client's delete -> delete shifts right")
        void serverInsertBeforeClientDelete() {
            // Document: "ABCDE"
            // Server: INSERT("X", pos=1) -> "AXBCDE"
            // Client: DELETE(pos=3, len=1) -> should shift to pos=4
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(1).text("X").length(1).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.DELETE).position(3).length(1).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            assertEquals(4, transformed.getPosition());
            assertEquals(1, transformed.getLength());
        }
    }

    @Nested
    @DisplayName("Transform: DELETE vs INSERT")
    class DeleteInsertTests {

        @Test
        @DisplayName("Server deletes before client's insert -> insert shifts left")
        void serverDeleteBeforeClientInsert() {
            // Document: "ABCDE"
            // Server: DELETE(pos=0, len=2) -> "CDE"
            // Client: INSERT("X", pos=4) -> should become pos=2
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.DELETE).position(0).length(2).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(4).text("X").length(1).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            assertEquals(2, transformed.getPosition());
        }

        @Test
        @DisplayName("Server deletes after client's insert -> insert stays")
        void serverDeleteAfterClientInsert() {
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.DELETE).position(4).length(1).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.INSERT).position(1).text("X").length(1).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            assertEquals(1, transformed.getPosition());
        }
    }

    @Nested
    @DisplayName("Transform: DELETE vs DELETE")
    class DeleteDeleteTests {

        @Test
        @DisplayName("Non-overlapping deletes: server before client")
        void nonOverlappingServerBeforeClient() {
            // Document: "ABCDEFGH"
            // Server: DELETE(pos=0, len=2) -> "CDEFGH"
            // Client: DELETE(pos=5, len=2) -> should become pos=3
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.DELETE).position(0).length(2).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.DELETE).position(5).length(2).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            assertEquals(3, transformed.getPosition());
            assertEquals(2, transformed.getLength());
        }

        @Test
        @DisplayName("Overlapping deletes reduce client's length")
        void overlappingDeletes() {
            // Document: "ABCDEFGH"
            // Server: DELETE(pos=2, len=3) -> "ABFGH" (deletes CDE)
            // Client: DELETE(pos=3, len=3) -> tries to delete DEF
            // But D and E are already gone! Only F remains to delete.
            TextOperation serverOp = TextOperation.builder()
                    .type(OperationType.DELETE).position(2).length(3).build();
            TextOperation clientOp = TextOperation.builder()
                    .type(OperationType.DELETE).position(3).length(3).build();

            TextOperation transformed = engine.transform(serverOp, clientOp);

            // Only 1 char left to delete (F), positioned at server's delete point
            assertEquals(2, transformed.getPosition());
            assertEquals(1, transformed.getLength());
        }
    }

    // ======================== CONVERGENCE PROPERTY TESTS ========================

    @Nested
    @DisplayName("Convergence property verification")
    class ConvergenceTests {

        @Test
        @DisplayName("Two inserts converge to same result regardless of order")
        void twoInsertsConverge() {
            String doc = "Hello";
            TextOperation opA = TextOperation.builder()
                    .type(OperationType.INSERT).position(0).text("X").length(1).build();
            TextOperation opB = TextOperation.builder()
                    .type(OperationType.INSERT).position(5).text("Y").length(1).build();

            // Path 1: Apply A first, then transform(B, A)
            String afterA = engine.apply(doc, opA);
            TextOperation bPrime = engine.transform(opA, opB);
            String result1 = engine.apply(afterA, bPrime);

            // Path 2: Apply B first, then transform(A, B)
            String afterB = engine.apply(doc, opB);
            TextOperation aPrime = engine.transform(opB, opA);
            String result2 = engine.apply(afterB, aPrime);

            assertEquals(result1, result2, "Both paths must converge to the same document");
        }

        @Test
        @DisplayName("Insert and delete converge to same result")
        void insertDeleteConverge() {
            String doc = "ABCDE";
            TextOperation opA = TextOperation.builder()
                    .type(OperationType.INSERT).position(2).text("X").length(1).build();
            TextOperation opB = TextOperation.builder()
                    .type(OperationType.DELETE).position(3).length(1).build();  // delete 'D'

            String afterA = engine.apply(doc, opA);           // "ABXCDE"
            TextOperation bPrime = engine.transform(opA, opB);
            String result1 = engine.apply(afterA, bPrime);     // should delete 'D' -> "ABXCE"

            String afterB = engine.apply(doc, opB);            // "ABCE"
            TextOperation aPrime = engine.transform(opB, opA);
            String result2 = engine.apply(afterB, aPrime);     // should insert 'X' -> "ABXCE"

            assertEquals(result1, result2);
            assertEquals("ABXCE", result1);
        }
    }
}
