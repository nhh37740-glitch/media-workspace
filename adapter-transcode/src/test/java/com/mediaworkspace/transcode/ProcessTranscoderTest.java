package com.mediaworkspace.transcode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessTranscoderTest {

    @Test
    void usesEncodedTimeAsFractionOfSourceDurationForBothFfmpegKeys() {
        List<Integer> reported = new ArrayList<>();

        ProcessTranscoder.reportProgress("out_time_us=99000000", 600_000, reported::add);
        ProcessTranscoder.reportProgress("out_time_ms=300000000", 600_000, reported::add);
        ProcessTranscoder.reportProgress("out_time_us=600000000", 600_000, reported::add);
        ProcessTranscoder.reportProgress("out_time_ms=900000000", 600_000, reported::add);
        ProcessTranscoder.reportProgress("progress=end", 600_000, reported::add);

        assertThat(reported).containsExactly(16, 50, 99, 99);
    }

    @Test
    void ignoresUnusableHintsAndUnknownDuration() {
        List<Integer> reported = new ArrayList<>();

        ProcessTranscoder.reportProgress("out_time_us=99000000", 0, reported::add);
        ProcessTranscoder.reportProgress("out_time_ms=99000000", -1, reported::add);
        ProcessTranscoder.reportProgress("out_time_us=bad", 600_000, reported::add);
        ProcessTranscoder.reportProgress("out_time_us=9223372036854775808", 600_000, reported::add);
        ProcessTranscoder.reportProgress("out_time_us=-1000", 600_000, reported::add);
        ProcessTranscoder.reportProgress("out_time_us", 600_000, reported::add);
        ProcessTranscoder.reportProgress("frame=1000", 600_000, reported::add);
        ProcessTranscoder.reportProgress("progress=end", 600_000, reported::add);

        assertThat(reported).isEmpty();
    }

    @Test
    void handlesExtremeDurationAndEncodedTimeWithoutOverflow() {
        List<Integer> reported = new ArrayList<>();

        ProcessTranscoder.reportProgress("out_time_us=" + Long.MAX_VALUE,
                Long.MAX_VALUE, reported::add);
        ProcessTranscoder.reportProgress("out_time_us=" + Long.MAX_VALUE,
                1, reported::add);

        assertThat(reported).containsExactly(0, 99);
    }
}
