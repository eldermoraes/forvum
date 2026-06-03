package ai.forvum.core.budget;

import java.time.ZoneId;

/** A calendar-day window resetting at midnight in {@code tz}. The zone is resolved at config-parse time. */
public record DayWindow(ZoneId tz) implements Window {
    public DayWindow {
        if (tz == null) {
            throw new IllegalStateException(
                "DayWindow tz must be non-null. Config-parse boundary "
              + "substitutes ZoneId.systemDefault() when the \"timezone\" "
              + "field is absent from agents/<id>.json or crons/<id>.json, "
              + "so a null reaching this constructor indicates a wiring bug.");
        }
    }
}
