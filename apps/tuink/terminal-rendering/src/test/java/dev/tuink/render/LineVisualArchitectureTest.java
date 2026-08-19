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
}
