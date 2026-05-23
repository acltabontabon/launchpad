package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Alignment;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * Welcome-screen rocket animation. Three phases on a continuous loop:
 *
 * <ul>
 *   <li><b>Landed</b> (long dwell) - rocket parked on the launch pad, no
 *       flames, no smoke; stars twinkle overhead.</li>
 *   <li><b>Liftoff</b> - brief ignition pause (engines lit, rocket still on
 *       the pad), then the rocket climbs from the pad to off-screen above,
 *       leaving an expanding exhaust trail.</li>
 *   <li><b>Afterglow</b> - smoke wisps fade near where the rocket exited;
 *       cycle wraps back to landed.</li>
 * </ul>
 *
 * <p>The scene anchors to a ground line at the bottom of the area. The
 * landed rocket sits with its bottom directly on the row above the ground,
 * so there is a clear "before" state - the rocket is visibly parked, not
 * already in flight.
 */
public final class RocketAnimation {

    private RocketAnimation() {}

    private static final String GROUND = "━━━━━━━━━━━━━━━━━━━━━";

    // Phase durations in render ticks (~80ms each, 12 fps).
    private static final long LANDED_TICKS = 72;        // 6.0s - the dwell
    private static final long LIFTOFF_TICKS = 30;       // 2.5s
    private static final long AFTERGLOW_TICKS = 18;     // 1.5s
    private static final long IGNITION_SUBTICKS = 6;    // first 0.5s of liftoff stays on the pad

    private static final long LANDED_END = LANDED_TICKS;
    private static final long LIFTOFF_END = LANDED_END + LIFTOFF_TICKS;
    private static final long CYCLE = LIFTOFF_END + AFTERGLOW_TICKS;

    // Rocket geometry: 6 parts, nose at offset 0, bottom at offset 5.
    private static final int ROCKET_HEIGHT = 6;

    public static Paragraph render(long tick, int areaHeight) {
        if (areaHeight < 4) areaHeight = 4;
        long pos = Math.floorMod(tick, CYCLE);

        Line[] lines = new Line[areaHeight];
        for (int i = 0; i < areaHeight; i++) lines[i] = blank();
        lines[areaHeight - 1] = text(GROUND, groundStyle());

        if (pos < LANDED_END) {
            paintLanded(lines, areaHeight, pos);
        } else if (pos < LIFTOFF_END) {
            paintLiftoff(lines, areaHeight, pos - LANDED_END);
        } else {
            paintAfterglow(lines, areaHeight, pos - LIFTOFF_END);
        }

        return Paragraph.builder()
            .text(Text.from(lines))
            .alignment(Alignment.CENTER)
            .build();
    }

    // ── styles ──────────────────────────────────────────────────────────────

    private static Style rocketStyle() { return Style.create().fg(Theme.brand).bold(); }
    private static Style flameStyle()  { return Style.create().fg(Theme.fuel).bold(); }
    private static Style windowStyle() { return Style.create().fg(Theme.ignition).bold(); }
    private static Style smokeStyle()  { return Style.create().fg(Theme.subtle); }
    private static Style starStyle()   { return Style.create().fg(Theme.subtle); }
    private static Style groundStyle() { return Style.create().fg(Theme.border); }

    // ── primitives ──────────────────────────────────────────────────────────

    private static Line blank() {
        return Line.from(Span.styled("", Style.create()));
    }

    private static Line text(String s, Style style) {
        return Line.from(Span.styled(s, style));
    }

    private static Line stars(long phase) {
        int idx = (int) Math.floorMod(phase, 4);
        String[] patterns = {
            "✦  ·  ·  ·  ·",
            "·  ✦  ·  ·  ·",
            "·  ·  ·  ✦  ·",
            "·  ·  ·  ·  ✦"
        };
        return text(patterns[idx], starStyle());
    }

    // ── phases ──────────────────────────────────────────────────────────────

    /**
     * Landed: rocket parked on the pad. Bottom sits directly above the
     * ground line. No flames, no smoke. Stars twinkle above the nose.
     */
    private static void paintLanded(Line[] lines, int areaHeight, long pos) {
        int starIdx = (int) Math.floorMod(pos / 9, 4);

        // Stack bottom-up: rocket bottom on row areaHeight - 2,
        // 6 rocket parts going up to areaHeight - 7.
        int row = areaHeight - 2;
        paint(lines, row--, "◥███◤", rocketStyle());  // bottom on the pad
        paint(lines, row--, "█████", rocketStyle());
        paintWindow(lines, row--);
        paint(lines, row--, "█████", rocketStyle());
        paint(lines, row--, "▲▲▲",   rocketStyle());
        paint(lines, row--, "▲",     rocketStyle());
        if (row >= 0) lines[row] = stars(starIdx);    // sky directly above the nose
    }

    /**
     * Liftoff. The first {@link #IGNITION_SUBTICKS} ticks are an ignition
     * pause - rocket still on the pad with flames lit at the base (the
     * flame overwrites the ground row, reading as engines firing on the
     * launchpad). After ignition, the nose interpolates linearly from the
     * landed position ({@code areaHeight - 7}) to off-screen ({@code -7}).
     */
    private static void paintLiftoff(Line[] lines, int areaHeight, long pos) {
        int landedNose = areaHeight - 7;
        int endNose = -7;
        int noseRow;

        if (pos < IGNITION_SUBTICKS) {
            // engines firing, rocket still parked
            noseRow = landedNose;
        } else {
            double progress = (double) (pos - IGNITION_SUBTICKS)
                / (LIFTOFF_TICKS - IGNITION_SUBTICKS);
            noseRow = (int) Math.round(landedNose + (endNose - landedNose) * progress);
        }

        // Paint into every row including the ground row - flame/smoke
        // is meant to engulf the launch pad as the rocket lifts off.
        for (int row = 0; row < areaHeight; row++) {
            int rIdx = row - noseRow;
            Line slice = rocketSlice(rIdx);
            if (slice != null) lines[row] = slice;
        }
    }

    private static Line rocketSlice(int rIdx) {
        if (rIdx >= 0 && rIdx < ROCKET_HEIGHT) {
            if (rIdx == 3) return windowLine();
            String[] parts = {"▲", "▲▲▲", "█████", "", "█████", "◥███◤"};
            return text(parts[rIdx], rocketStyle());
        }
        if (rIdx == ROCKET_HEIGHT) return text("▼▼▼", flameStyle());
        if (rIdx >= ROCKET_HEIGHT + 1 && rIdx <= ROCKET_HEIGHT + 10) {
            String[] trail = {
                "░ ░ ░",
                "░░░░░",
                "░░ ░ ░░",
                "░░░ ░ ░░░",
                "░░ ░ ░ ░░",
                "░ ░░ ░░ ░",
                "░  ░  ░",
                "░ ░ ░",
                "  ░ ░  ",
                "   ░   "
            };
            return text(trail[rIdx - ROCKET_HEIGHT - 1], smokeStyle());
        }
        return null;
    }

    /**
     * Afterglow: smoke wisps fade near where the rocket exited; ground
     * line re-emerges; cycle wraps to landed.
     */
    private static void paintAfterglow(Line[] lines, int areaHeight, long pos) {
        int sub = (int) (pos / 6);
        if (areaHeight > 2) lines[1] = stars(pos);

        int upper = 3, middle = 5, lower = 7;
        if (sub == 0) {
            paintIfRoom(lines, areaHeight, upper,  "░ ░ ░");
            paintIfRoom(lines, areaHeight, middle, "░░ ░ ░░");
            paintIfRoom(lines, areaHeight, lower,  "░░░ ░ ░░░");
        } else if (sub == 1) {
            paintIfRoom(lines, areaHeight, upper + 1,  " ░ ░ ");
            paintIfRoom(lines, areaHeight, middle + 1, "░ ░ ░");
        } else {
            paintIfRoom(lines, areaHeight, middle + 2, "  ░  ");
        }
    }

    // ── paint helpers ───────────────────────────────────────────────────────

    private static void paint(Line[] lines, int row, String content, Style style) {
        if (row >= 0 && row < lines.length) lines[row] = text(content, style);
    }

    private static void paintIfRoom(Line[] lines, int areaHeight, int row, String s) {
        if (row >= 0 && row < areaHeight - 1) lines[row] = text(s, smokeStyle());
    }

    private static void paintWindow(Line[] lines, int row) {
        if (row >= 0 && row < lines.length) lines[row] = windowLine();
    }

    private static Line windowLine() {
        return Line.from(
            Span.styled("█ ", rocketStyle()),
            Span.styled("●", windowStyle()),
            Span.styled(" █", rocketStyle())
        );
    }
}
