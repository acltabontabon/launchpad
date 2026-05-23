package com.acltabontabon.launchpad.tui.components;

import com.acltabontabon.launchpad.tui.theme.Styles;
import com.acltabontabon.launchpad.tui.theme.Theme;
import dev.tamboui.layout.Padding;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;

/**
 * The standard panel for the Cosmic Console redesign: rounded corners,
 * 1-cell internal padding, brand-coloured border when active and a subtle
 * gray border when inactive. Title (and optional bottom title) sit in the
 * border itself.
 *
 * <pre>{@code
 *   var block = Card.of("project path").active(focused).build();
 *   frame.renderWidget(Paragraph.builder().block(block).text(...).build(), area);
 * }</pre>
 */
public final class Card {

    private final String title;
    private String bottomTitle;
    private boolean active = false;
    private int horizontalPadding = 1;
    private int verticalPadding = 0;

    private Card(String title) {
        this.title = title;
    }

    public static Card of(String title) {
        return new Card(title);
    }

    public Card active(boolean active) {
        this.active = active;
        return this;
    }

    public Card bottomTitle(String bottomTitle) {
        this.bottomTitle = bottomTitle;
        return this;
    }

    /** Default padding is (0 vertical, 1 horizontal). Use this to override. */
    public Card padding(int vertical, int horizontal) {
        this.verticalPadding = vertical;
        this.horizontalPadding = horizontal;
        return this;
    }

    public Block build() {
        var borderColor = active ? Theme.brand : Theme.border;
        var titleStyle = active ? Styles.brandHeading() : Styles.subheading();

        var builder = Block.builder()
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderColor(borderColor)
            .padding(Padding.symmetric(verticalPadding, horizontalPadding));

        if (title != null && !title.isBlank()) {
            // Title format: "─ title ─" feel via spacing inside the rendered line.
            var titleLine = Line.from(
                Span.styled(" " + title + " ", titleStyle)
            );
            builder.title(Title.from(titleLine).left());
        }
        if (bottomTitle != null && !bottomTitle.isBlank()) {
            var bottomLine = Line.from(
                Span.styled(" " + bottomTitle + " ", Styles.caption())
            );
            builder.titleBottom(Title.from(bottomLine).right());
        }
        return builder.build();
    }
}
