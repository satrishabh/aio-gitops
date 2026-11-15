package com.aio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class FreezePolicyTest {
    static final String SPEC = "prod@FRI 18:00/MON 09:00#weekend; prod, staging@2026-12-24T00:00/2026-12-27T00:00#holidays";

    /** Times are in Asia/Kolkata (UTC+05:30). */
    private static FreezePolicy at(String spec, String utcInstant) {
        Clock c = Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
        return new FreezePolicy(spec, "Asia/Kolkata", new StaticListableBeanFactory(Map.of("clock", c)).getBeanProvider(Clock.class));
    }

    @Test
    void weeklyWindowWrapsOverTheWeekend() {
        // Fri 20 Nov 2026 18:30 IST = 13:00 UTC
        var f = at(SPEC, "2026-11-20T13:00:00Z").active("prod").orElseThrow();
        assertThat(f.reason()).isEqualTo("weekend");
        assertThat(f.until()).isEqualTo(Instant.parse("2026-11-23T03:30:00Z"));   // Mon 09:00 IST
        assertThat(at(SPEC, "2026-11-22T10:00:00Z").active("prod")).isPresent();   // Sunday
        assertThat(at(SPEC, "2026-11-23T03:31:00Z").active("prod")).isEmpty();     // Mon 09:01 IST
        assertThat(at(SPEC, "2026-11-20T12:29:00Z").active("prod")).isEmpty();     // Fri 17:59 IST
    }

    @Test
    void weekdayNamesContainingTAreWeeklyNotDates() {
        var p = at("prod@SAT 00:00/TUE 06:00#maintenance", "2026-11-23T10:00:00Z");   // Monday
        assertThat(p.active("prod").orElseThrow().reason()).isEqualTo("maintenance");
        assertThat(at("prod@THU 22:00/THU 23:00", "2026-11-19T16:45:00Z").active("prod")).isPresent();   // Thu 22:15 IST
    }

    @Test
    void windowsOnlyCoverTheirEnvironments() {
        assertThat(at(SPEC, "2026-11-21T10:00:00Z").active("staging")).isEmpty();
        assertThat(at(SPEC, "2026-12-25T10:00:00Z").active("staging").orElseThrow().reason()).isEqualTo("holidays");
        assertThat(at("*@2026-12-24T00:00/2026-12-27T00:00", "2026-12-25T10:00:00Z").active("dev")).isPresent();
    }

    @Test
    void overlappingWindowsReportTheLongest() {
        // Sat 26 Dec: weekend ends Mon 28 Dec 09:00, holidays end Sun 27 Dec 00:00 -> weekend wins
        var f = at(SPEC, "2026-12-26T06:00:00Z").active("prod").orElseThrow();
        assertThat(f.reason()).isEqualTo("weekend");
    }

    @Test
    void requireRefusesWithAnExplanation() {
        assertThatThrownBy(() -> at(SPEC, "2026-11-21T10:00:00Z").require("prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod is frozen until").hasMessageContaining("weekend").hasMessageContaining("admin can override");
        at(SPEC, "2026-11-18T10:00:00Z").require("prod");   // Wednesday: open
    }

    @Test
    void emptyConfigFreezesNothing() {
        assertThat(at("", "2026-11-21T10:00:00Z").active("prod")).isEmpty();
        assertThat(FreezePolicy.none().describe()).isEmpty();
    }

    @Test
    void describeListsWindowsWithTheirState() {
        var d = at(SPEC, "2026-11-21T10:00:00Z").describe();
        assertThat(d).hasSize(2);
        assertThat(d.get(0)).containsEntry("active", true).containsEntry("reason", "weekend");
        assertThat(d.get(1)).containsEntry("active", false);
        assertThat((String) d.get(0).get("when")).contains("every Fri 18:00 to Mon 09:00");
    }

    @Test
    void typosStopStartupInsteadOfSilentlyDisablingAFreeze() {
        assertThatThrownBy(() -> at("prod@FRY 18:00/MON 09:00", "2026-11-21T10:00:00Z")).hasMessageContaining("invalid AIO_FREEZE_WINDOWS");
        assertThatThrownBy(() -> at("prod 2026-12-24T00:00/2026-12-27T00:00", "2026-11-21T10:00:00Z")).hasMessageContaining("<envs>@<when>");
        assertThatThrownBy(() -> at("prod@2026-12-27T00:00/2026-12-24T00:00", "2026-11-21T10:00:00Z")).hasMessageContaining("end must be after start");
    }
}
