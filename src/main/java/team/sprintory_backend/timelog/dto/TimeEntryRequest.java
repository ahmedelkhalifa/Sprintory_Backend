package team.sprintory_backend.timelog.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.time.LocalDate;

/** Minutes are canonical. A missing creation date defaults to server today. */
public record TimeEntryRequest(LocalDate workDate,
        @NotNull @Min(1) @Max(1440) Integer minutes,
        @Size(max = 500) String note) {

    // Do not allow Jackson's numeric coercion to silently truncate fractional minutes.
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static TimeEntryRequest fromJson(@JsonProperty("workDate") LocalDate workDate,
                                            @JsonProperty("minutes") JsonNode minutes,
                                            @JsonProperty("note") String note) {
        if (minutes == null || minutes.isNull()) return new TimeEntryRequest(workDate, null, note);
        if (!minutes.isIntegralNumber() || !minutes.canConvertToLong()
                || minutes.longValue() < Integer.MIN_VALUE || minutes.longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Minutes must be a whole integer.");
        return new TimeEntryRequest(workDate, (int) minutes.longValue(), note);
    }
}
