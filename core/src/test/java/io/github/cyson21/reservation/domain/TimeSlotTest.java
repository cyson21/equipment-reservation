package io.github.cyson21.reservation.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSlotTest {

    private static final Instant START = Instant.parse("2030-01-01T14:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    @Test
    void overlappingIntervalsIncludeEqualAndContainedSlots() {
        var slot = new TimeSlot(START, END);
        var contained = new TimeSlot(START.plusSeconds(60), END.minusSeconds(60));

        assertTrue(slot.overlaps(slot));
        assertTrue(slot.overlaps(contained));
        assertTrue(contained.overlaps(slot));
    }

    @Test
    void anIntervalEndingAtTheNextStartDoesNotOverlap() {
        var first = new TimeSlot(START, END);
        var second = new TimeSlot(END, END.plusSeconds(3600));

        assertFalse(first.overlaps(second));
        assertFalse(second.overlaps(first));
    }

    @Test
    void rejectsEmptyAndReversedIntervals() {
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(START, START));
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(END, START));
    }

    @Test
    void rejectsMissingTimes() {
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(null, END));
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(START, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-12-31T23:59:59.999999Z", "2029-12-31T23:59:59.999999999Z"})
    void rejectsStartsOutsideTheSupportedRangeOrPrecision(String invalidStart) {
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(Instant.parse(invalidStart), END));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+10000-01-01T00:00:00Z", "2030-01-01T14:30:00.000000001Z"})
    void rejectsEndsOutsideTheSupportedRangeOrPrecision(String invalidEnd) {
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(START, Instant.parse(invalidEnd)));
    }

    @Test
    void acceptsTheFirstAndLastMicrosecondInTheSupportedUtcRange() {
        var first = Instant.parse("0001-01-01T00:00:00Z");
        var last = Instant.parse("9999-12-31T23:59:59.999999Z");

        assertTrue(new TimeSlot(first, last).overlaps(new TimeSlot(START, END)));
    }
}
