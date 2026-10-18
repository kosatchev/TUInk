package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownBlockContextTest {
    @Test
    void continuationAndTerminationShareTheCodeBlockBoundary() {
        for (List<String> prefix : List.of(
            List.<String>of(), List.of("paragraph", ""),
            List.of("- parent", "    ```sh"), List.of("- parent", "    ~~~sh"),
            List.of("- ```sh"), List.of("123. ~~~sh"),
            List.of("- parent", "", "    ```sh"),
            List.of("- parent", "    - child", "        ```sh"),
            List.of("123. parent", "     ```sh"),
            List.of("-     indented code")
        )) {
            for (String marker : List.of("- ", "- [ ] ", "1. ")) {
                List<String> lines = new ArrayList<>(prefix);
                String indent = prefix.equals(List.of("123. ~~~sh")) ? "     "
                    : prefix.equals(List.of("123. parent", "     ```sh")) ? "     "
                    : prefix.contains("        ```sh") || prefix.equals(List.of("-     indented code"))
                        ? "        " : "    ";
                lines.add(indent + marker + "literal code");
                Fixture fixture = new Fixture(lines);
                int last = lines.size() - 1;
                MarkdownListDecisions decisions = fixture.decisions();

                assertEquals("", decisions.continuation(last, lines.getLast()), lines.toString());
                lines.set(last, indent + marker);
                fixture.synchronize(0, 1, last);
                assertNull(decisions.termination(new Position(last, lines.getLast().length())), lines.toString());
            }
        }
    }

    @Test
    void realNestedItemsRemainListsAndDedentsLeaveNestedFences() {
        for (List<String> lines : List.of(
            List.of("- parent", "    - child"),
            List.of("- parent", "    - child", "        - grandchild"),
            List.of("- parent", "", "    - child"),
            List.of("- parent", "    ```sh", "    - code", "- outside"),
            List.of("- ```sh", "  - code", "- outside"),
            List.of("123. parent", "     ~~~sh", "     - code", "1. outside"),
            List.of("- parent", "    ```sh", "    ```", "    - child")
        )) {
            Fixture fixture = new Fixture(lines);
            int last = lines.size() - 1;
            assertTrue(fixture.context.allowsListAssist(last), lines.toString());
            assertFalse(fixture.decisions().continuation(last, lines.getLast()).isEmpty(), lines.toString());
        }
    }

    @Test
    void fenceStateSurvivesLongBodiesAndQueriesReuseNearbyCheckpoints() {
        List<String> lines = new ArrayList<>(Collections.nCopies(20_000, "    body"));
        lines.set(0, "- parent");
        lines.set(1, "    ````sh");
        lines.set(10_000, "");
        lines.set(19_999, "    - literal");
        Fixture fixture = new Fixture(lines);

        assertFalse(fixture.context.allowsListAssist(19_999));
        assertEquals(20_000, fixture.reads);
        for (int query = 0; query < 10; query++) {
            assertFalse(fixture.context.allowsListAssist(19_999));
        }
        assertEquals(20_000, fixture.reads);

        assertFalse(fixture.context.allowsListAssist(10_000));
        assertTrue(fixture.reads <= 20_256, "An earlier query starts at a nearby checkpoint");
        int afterEarlierQuery = fixture.reads;
        for (int query = 0; query < 10; query++) {
            assertFalse(fixture.context.allowsListAssist(10_000));
        }
        assertEquals(afterEarlierQuery, fixture.reads);
        assertFalse(fixture.context.allowsListAssist(19_999));
        assertTrue(fixture.reads <= afterEarlierQuery + 256);
        int beforeAppend = fixture.reads;

        lines.add("    - next literal");
        fixture.synchronize(0, 1, 20_000);
        assertFalse(fixture.context.allowsListAssist(20_000));
        assertEquals(beforeAppend + 1, fixture.reads, "An appended line reuses the parsed prefix");
    }

    @Test
    void changedSuffixRetainsTheEarlierContextAndUpdatesFenceBoundaries() {
        List<String> lines = new ArrayList<>(List.of("- parent", "    ```sh", "    - literal", "    ```", "    - child"));
        Fixture fixture = new Fixture(lines);
        assertTrue(fixture.context.allowsListAssist(4));
        assertEquals(5, fixture.reads);

        lines.set(3, "    ```not-a-close");
        fixture.synchronize(0, 1, 3);
        assertFalse(fixture.context.allowsListAssist(4));

        lines.add(2, "");
        fixture.synchronize(0, 2, 2);
        assertFalse(fixture.context.allowsListAssist(5));

        lines.remove(1);
        fixture.synchronize(0, 3, 1);
        assertFalse(fixture.context.allowsListAssist(4), "The former closer is now an opening fence");
        lines.set(3, "    ordinary text");
        fixture.synchronize(0, 4, 3);
        assertTrue(fixture.context.allowsListAssist(4));
    }

    @Test
    void editingTheQueriedLinePreservesItsIncomingState() {
        List<String> lines = new ArrayList<>(Collections.nCopies(20_000, "body"));
        lines.set(0, "```sh");
        lines.set(19_999, "- literal");
        Fixture fixture = new Fixture(lines);
        assertFalse(fixture.context.allowsListAssist(19_999));
        fixture.reads = 0;

        lines.set(19_999, "- another literal");
        fixture.synchronize(0, 1, 19_999);
        assertFalse(fixture.context.allowsListAssist(19_999));
        assertEquals(1, fixture.reads);

        lines.add("- next literal");
        fixture.synchronize(0, 2, 19_999);
        assertFalse(fixture.context.allowsListAssist(20_000));
        assertEquals(3, fixture.reads, "Enter reparses only the changed line and its new successor");
    }

    @Test
    void insertionsAndDeletionsAtACheckpointPreserveOnlyItsIncomingState() {
        List<String> lines = new ArrayList<>(Collections.nCopies(300, "    body"));
        Collections.fill(lines.subList(0, 255), "paragraph");
        lines.set(255, "- parent");
        lines.set(256, "    ```sh");
        lines.set(299, "    - literal");
        Fixture fixture = new Fixture(lines);
        assertFalse(fixture.context.allowsListAssist(299));

        lines.remove(256);
        fixture.synchronize(0, 1, 256);
        assertTrue(fixture.context.allowsListAssist(298));

        lines.add(256, "    ```sh");
        fixture.synchronize(0, 2, 256);
        assertFalse(fixture.context.allowsListAssist(299));
        assertFalse(fixture.context.allowsListAssist(256));

        lines.set(256, "    - child");
        fixture.synchronize(0, 3, 256);
        assertTrue(fixture.context.allowsListAssist(256));
        assertTrue(fixture.context.allowsListAssist(299));
    }

    @Test
    void documentReplacementAndUnknownChangeHistoryReparseFromTheStart() {
        List<String> lines = new ArrayList<>(List.of("```sh", "- code"));
        Fixture fixture = new Fixture(lines);
        assertFalse(fixture.context.allowsListAssist(1));

        lines.set(0, "ordinary text");
        fixture.synchronize(1, 1, lines.size());
        assertTrue(fixture.context.allowsListAssist(1));
        assertEquals(4, fixture.reads);

        lines.set(0, "~~~");
        fixture.synchronize(1, 500, 0);
        assertFalse(fixture.context.allowsListAssist(1));
        assertEquals(6, fixture.reads);
        assertThrows(IndexOutOfBoundsException.class, () -> fixture.context.allowsListAssist(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> fixture.context.allowsListAssist(2));
    }

    @Test
    void longNestedMarkerChainsDoNotRepeatedlySearchTheirThematicBreakSuffix() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (String suffix : List.of("x", "* - - -", "x - - -")) {
                Fixture fixture = new Fixture(List.of("- ".repeat(100_000) + suffix, "    - child"));

                assertTrue(fixture.context.allowsListAssist(0), suffix);
                assertTrue(fixture.context.allowsListAssist(1), suffix);
                assertEquals(2, fixture.reads);
            }
        });
    }

    private static final class Fixture {
        final List<String> lines;
        final MarkdownBlockContext context;
        int reads;

        Fixture(List<String> lines) {
            this.lines = lines;
            context = new MarkdownBlockContext(line -> {
                reads++;
                return lines.get(line);
            });
            synchronize(0, 0, 0);
        }

        void synchronize(long epoch, long revision, int firstChangedLine) {
            context.synchronize(epoch, revision, firstChangedLine, lines.size());
        }

        MarkdownListDecisions decisions() {
            return new MarkdownListDecisions(lines::get, context::allowsListAssist);
        }
    }
}
