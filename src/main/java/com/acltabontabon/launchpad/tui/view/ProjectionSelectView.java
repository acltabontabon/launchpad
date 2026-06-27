package com.acltabontabon.launchpad.tui.view;

import com.acltabontabon.launchpad.config.LaunchpadSettings;
import com.acltabontabon.launchpad.template.projection.AgentProjection;
import com.acltabontabon.launchpad.tui.AppState;
import com.acltabontabon.launchpad.tui.components.Card;
import com.acltabontabon.launchpad.tui.components.KeyHint;
import com.acltabontabon.launchpad.tui.mcp.AiClient;
import com.acltabontabon.launchpad.tui.mcp.ClientId;
import com.acltabontabon.launchpad.tui.mcp.ClientRegistry;
import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * One-time picker: which AI tools does the developer use locally?
 * <p>
 * Shows a row per registered {@link AgentProjection} bean. Rows are
 * pre-ticked based on (a) the developer's already-persisted preference if
 * any, otherwise (b) a filesystem probe of installed MCP clients via
 * {@link ClientRegistry}. The chosen set is persisted to
 * {@code ~/.launchpad/config.properties} and never asked again, except
 * when re-entered explicitly from the settings screen.
 */
@Component
public class ProjectionSelectView implements View {

    /** Maps a projection id to the MCP-client ids whose presence implies
     *  the developer probably uses that agent. Used for pre-ticking. */
    private static final Map<String, Set<ClientId>> PROJECTION_CLIENTS = Map.of(
        "claude", Set.of(ClientId.CLAUDE_DESKTOP, ClientId.CLAUDE_CODE)
    );

    private final List<AgentProjection> projections;
    private final LaunchpadSettings settings;
    private final ClientRegistry clientRegistry;

    public ProjectionSelectView(List<AgentProjection> projections,
                                LaunchpadSettings settings,
                                ClientRegistry clientRegistry) {
        this.projections = projections;
        this.settings = settings;
        this.clientRegistry = clientRegistry;
    }

    /**
     * Seeds {@link AppState#projectionPickerSelected} from the user's
     * current preference if any, otherwise from a filesystem probe.
     * LaunchpadRunner calls this once when entering the screen.
     */
    public void seedSelection(AppState state) {
        var snap = settings.snapshot();
        if (snap.projections() != null) {
            state.projectionPickerSelected = new LinkedHashSet<>(snap.projections());
        } else {
            state.projectionPickerSelected = autoDetect();
        }
        state.projectionPickerCursor = 0;
    }

    private Set<String> autoDetect() {
        var detected = new LinkedHashSet<String>();
        var clients = clientRegistry.discover();
        for (var p : projections) {
            var clientIds = PROJECTION_CLIENTS.get(p.id());
            if (clientIds == null) continue;
            for (var ac : clients) {
                if (clientIds.contains(ac.id()) && ac.detected()) {
                    detected.add(p.id());
                    break;
                }
            }
        }
        return detected;
    }

    @Override
    public void render(Frame frame, Rect area, AppState state) {
        var rows = Layout.vertical()
            .constraints(
                Constraint.length(2),
                Constraint.length(4),
                Constraint.length(1),
                Constraint.min(0)
            )
            .split(area);

        renderHeading(frame, rows.get(1));
        renderList(frame, rows.get(3), state);
    }

    private static void renderHeading(Frame frame, Rect area) {
        var content = Text.from(
            Line.from(Span.styled("  Which AI tools do you use?", Styles.heading())),
            Line.from(Span.styled(
                "  Launchpad will emit agent-native discovery files only for the tools you pick.",
                Styles.caption())),
            Line.from(Span.styled(
                "  AGENTS.md and .ai/ are vendor-neutral and always emitted.",
                Styles.caption()))
        );
        frame.renderWidget(Paragraph.builder().text(content).build(), area);
    }

    private void renderList(Frame frame, Rect area, AppState state) {
        var card = Card.of(projections.size() + " available").active(true).build();
        var inner = card.inner(area);
        frame.renderWidget(card, area);

        if (projections.isEmpty()) {
            var p = Paragraph.builder().text(Text.from(Line.from(Span.styled(
                "  No agent projections registered. Nothing to pick.", Styles.caption()
            )))).build();
            frame.renderWidget(p, inner);
            return;
        }

        var lines = new ArrayList<Line>();
        for (int i = 0; i < projections.size(); i++) {
            var projection = projections.get(i);
            var checked = state.projectionPickerSelected.contains(projection.id());
            var focused = i == state.projectionPickerCursor;
            var rowStyle = focused
                ? Style.create().fg(Theme.fuel).bold()
                : Styles.code();
            var marker = focused ? " > " : "   ";
            var box = checked ? "[x] " : "[ ] ";
            lines.add(Line.from(
                Span.styled(marker, rowStyle),
                Span.styled(box, rowStyle),
                Span.styled(projection.displayName(), rowStyle)
            ));
            if (projection.description() != null && !projection.description().isBlank()) {
                lines.add(Line.from(Span.styled(
                    "       " + projection.description(),
                    Styles.muted()
                )));
            }
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0]))).build(), inner);
    }

    @Override
    public List<KeyHint> footerHints(AppState state) {
        return List.of(
            new KeyHint("↑↓", "navigate"),
            new KeyHint("space", "toggle"),
            new KeyHint("enter", "save"),
            new KeyHint("esc", "back")
        );
    }

    @Override
    public boolean handleEvent(Event event, TuiRunner runner, AppState state) {
        if (!(event instanceof KeyEvent key)) return false;
        if (projections.isEmpty()) {
            if (key.isKey(KeyCode.ESCAPE) || key.isKey(KeyCode.ENTER)) {
                advance(state);
                return true;
            }
            return false;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            cancel(state);
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            state.projectionPickerCursor =
                (state.projectionPickerCursor - 1 + projections.size()) % projections.size();
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            state.projectionPickerCursor =
                (state.projectionPickerCursor + 1) % projections.size();
            return true;
        }
        if (key.isChar(' ')) {
            var id = projections.get(state.projectionPickerCursor).id();
            var working = new LinkedHashSet<>(state.projectionPickerSelected);
            if (!working.remove(id)) working.add(id);
            state.projectionPickerSelected = working;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            try {
                settings.updateProjections(new LinkedHashSet<>(state.projectionPickerSelected));
            } catch (IOException e) {
                // Persistence failed - still proceed so the user is not stuck.
                // The snapshot will revert on next launch and the picker will
                // reappear, which is the right escape hatch.
            }
            advance(state);
            return true;
        }
        return false;
    }

    private void advance(AppState state) {
        if (state.projectionPickerReturnsToSettings) {
            state.projectionPickerReturnsToSettings = false;
            state.nav.currentScreen = AppState.Screen.SETTINGS;
        } else {
            state.nav.currentScreen = AppState.Screen.SCANNING;
        }
    }

    private void cancel(AppState state) {
        if (state.projectionPickerReturnsToSettings) {
            state.projectionPickerReturnsToSettings = false;
            state.nav.currentScreen = AppState.Screen.SETTINGS;
        } else {
            state.nav.currentScreen = AppState.Screen.PROJECT_SELECT;
        }
    }
}
