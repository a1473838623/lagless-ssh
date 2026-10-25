package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.I18n.Language;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;

/** First-run language choice; later changes take effect after a restart. */
final class LanguageDialog {
    private LanguageDialog(){}
    static final String KEY="language";

    /** Applies the saved language, asking once when none has been chosen yet. */
    static void apply(Path home,boolean askIfMissing){
        try{
            DesktopPreferences preferences=new DesktopPreferences(home);
            Language saved=Language.parse(preferences.get(KEY,null));
            if(saved==null&&askIfMissing){saved=ask(null,Language.system());preferences.set(KEY,saved.code);}
            I18n.setLanguage(saved);
        }catch(Exception error){error.printStackTrace(System.err);}
    }
    /** Lets the user switch language from the menu; the window texts change on the next launch. */
    static void change(Component parent,DesktopPreferences preferences){
        Language chosen=ask(parent,I18n.language());
        if(chosen==I18n.language())return;
        preferences.set(KEY,chosen.code);
        JOptionPane.showMessageDialog(parent,chosen==Language.EN?"The new language is used after you restart Lagless.":"重新启动 Lagless 后使用新的语言。",
                chosen==Language.EN?"Language":"语言",JOptionPane.INFORMATION_MESSAGE);
    }
    static Language ask(Component parent,Language initial){
        Window owner=parent==null?null:DesktopApp.ownerWindow(parent);
        JDialog dialog=new JDialog(owner,"Lagless · Language / 语言",Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setIconImage(DesktopApp.appIcon());
        Language[] result={initial};
        JPanel root=new JPanel(new BorderLayout(0,18));root.setBackground(Color.WHITE);root.setBorder(new EmptyBorder(28,32,24,32));
        JPanel heading=new JPanel();heading.setOpaque(false);heading.setLayout(new BoxLayout(heading,BoxLayout.Y_AXIS));
        JLabel badge=new JLabel(new ImageIcon(DesktopApp.appIcon(40)));badge.setAlignmentX(0);heading.add(badge);heading.add(Box.createVerticalStrut(14));
        JLabel title=new JLabel("Welcome to Lagless");title.setFont(DesktopFonts.ui().deriveFont(Font.BOLD,22f));title.setForeground(DesktopApp.INK);title.setAlignmentX(0);heading.add(title);
        heading.add(Box.createVerticalStrut(4));
        JLabel subtitle=new JLabel("Choose your language  ·  选择界面语言");subtitle.setForeground(DesktopApp.MUTED);subtitle.setAlignmentX(0);heading.add(subtitle);
        root.add(heading,BorderLayout.NORTH);
        JPanel choices=new JPanel(new GridLayout(1,0,12,0));choices.setOpaque(false);
        ButtonGroup group=new ButtonGroup();
        for(Language language:Language.values()){
            JToggleButton card=choice(language);card.setSelected(language==initial);group.add(card);choices.add(card);
            card.addActionListener(e->result[0]=language);
            card.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2){result[0]=language;dialog.dispose();}}});
        }
        root.add(choices,BorderLayout.CENTER);
        JPanel actions=new JPanel(new BorderLayout());actions.setOpaque(false);
        JLabel note=new JLabel("<html>You can change this later under About.<br>之后可在「关于」菜单中更改。</html>");note.setForeground(DesktopApp.MUTED);note.setFont(DesktopFonts.ui().deriveFont(12f));actions.add(note,BorderLayout.CENTER);
        JButton ok=new JButton("Continue  ·  继续");ok.setName("language-continue");ok.putClientProperty("JButton.buttonType","roundRect");DesktopApp.primary(ok);ok.addActionListener(e->dialog.dispose());actions.add(ok,BorderLayout.EAST);
        root.add(actions,BorderLayout.SOUTH);
        dialog.setContentPane(root);dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e->{result[0]=initial;dialog.dispose();},KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();dialog.setResizable(false);dialog.setLocationRelativeTo(parent);dialog.setVisible(true);
        return result[0];
    }
    private static JToggleButton choice(Language language){
        String hint=language==Language.ZH?"中文界面":"English interface";
        JToggleButton card=new JToggleButton("<html><div style='padding:2px 4px'><b style='font-size:15px'>"+language.label+"</b><br><span style='color:#64748b'>"+hint+"</span></div></html>");
        card.setName("language-"+language.code);card.setFocusPainted(false);card.setHorizontalAlignment(SwingConstants.LEFT);
        card.setPreferredSize(new Dimension(210,74));card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.putClientProperty("FlatLaf.style","arc:14;borderWidth:1;selectedBackground:#e6f6f3;selectedForeground:#0f172a;focusWidth:0");
        return card;
    }
}
