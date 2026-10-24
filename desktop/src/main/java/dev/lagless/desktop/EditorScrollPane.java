package dev.lagless.desktop;

import javax.swing.*;
import java.awt.*;

/** Never copy old text pixels when scrolling a Swing fallback/editor surface. */
final class EditorScrollPane extends JScrollPane {
    EditorScrollPane(Component view){super(view);getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);}
}
