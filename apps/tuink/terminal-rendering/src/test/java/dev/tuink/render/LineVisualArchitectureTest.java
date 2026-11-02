package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LineVisualArchitectureTest {
    @Test
    void baseStyleCacheProtocolIsTypedAndOpaque() {
        RecordComponent baseStyleKey = Arrays.stream(LineVisual.class.getRecordComponents())
            .filter(component -> component.getName().equals("baseStyleKey"))
            .findFirst()
            .orElseThrow();

        assertAll(
            () -> assertEquals(BaseStyleIdentity.class, baseStyleKey.getType()),
            () -> assertTrue(Modifier.isFinal(BaseStyleIdentity.class.getModifiers())),
            () -> assertTrue(
                Arrays.stream(BaseStyleIdentity.class.getDeclaredConstructors())
                    .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()))
            ),
            () -> assertFalse(
                Arrays.stream(BaseStyleIdentity.class.getDeclaredMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getReturnType() == BaseStyleIdentity.class)
                    .anyMatch(method -> Modifier.isPublic(method.getModifiers())
                        || Modifier.isProtected(method.getModifiers()))
            ),
            () -> assertTrue(
                Arrays.stream(BaseStyleIdentity.class.getDeclaredFields())
                    .allMatch(field -> Modifier.isPrivate(field.getModifiers()))
            )
        );
    }

    @Test
    void identityRetainsThePreviousCacheEqualitySemantics() {
        BaseStyleIdentity first = BaseStyleIdentity.of(1, 2, 3, 4, 5, 6, 7);
        BaseStyleIdentity equal = BaseStyleIdentity.of(1, 2, 3, 4, 5, 6, 7);
        BaseStyleIdentity changedRevision = BaseStyleIdentity.of(1, 2, 3, 8, 5, 6, 7);

        assertAll(
            () -> assertEquals(first, equal),
            () -> assertEquals(first.hashCode(), equal.hashCode()),
            () -> assertNotEquals(first, changedRevision),
            () -> assertNotEquals(first, new Object())
        );
    }

    @Test
    void preparedRowProjectionKeepsEveryIdentityDimensionWithoutHashing() {
        var identity = BaseStyleIdentity.of(11, 22, 33, 44, 55, 66, 77);
        var key = identity.preparedRowKey(4, 3);
        assertAll(
            () -> assertEquals(11, key.producerId()),
            () -> assertEquals(22, key.sourceId()),
            () -> assertEquals(44, key.contentRevision()),
            () -> assertEquals(33, key.styleRevision()),
            () -> assertEquals(55, key.row()),
            () -> assertEquals(66, key.sliceStart()),
            () -> assertEquals(77, key.sliceLength()),
            () -> assertEquals(4, key.tabWidth()),
            () -> assertEquals(3, key.tabPhase()),
            () -> assertNotEquals(key, identity.preparedRowKey(4, 2)),
            () -> assertNotEquals(key, BaseStyleIdentity.of(11, 22, 34, 44, 55, 66, 77).preparedRowKey(4, 3))
        );
    }
}
