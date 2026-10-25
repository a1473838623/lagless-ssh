package dev.lagless.desktop;

import dev.lagless.I18n;
import javax.swing.*;
import java.awt.Component;

/** Real line breaks are reviewed at the boundary where text enters the terminal. */
final class MultilineInput {
    private MultilineInput(){}
    static String normalize(String text){return text.replace("\r\n","\n").replace('\r','\n');}
    static String prepare(Component parent,String original){
        String text=normalize(original);
        if(text.indexOf('\n')<0)return text;
        Object[] choices={I18n.t("合并为一行（软换行）","Join into one line (soft wrap)"),I18n.t("保留换行","Keep line breaks"),I18n.t("取消","Cancel")};
        int choice=JOptionPane.showOptionDialog(parent,
                I18n.t("内容包含真实换行符，是否合并为一行？\n合并：将换行替换为空格，终端按宽度自动折行。\n保留：发送完整多行内容，换行可能触发远端执行。","The text contains real line breaks. Join it into one line?\nJoin: replace line breaks with spaces; the terminal wraps by width.\nKeep: send all lines; each line break may run on the remote side."),
                I18n.t("发送前检查换行","Check line breaks before sending"),JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,choices,choices[0]);
        return choice==0?text.replace('\n',' '):choice==1?text:null;
    }
    static boolean unsupportedControls(String text){return text.chars().anyMatch(c->c<32&&c!='\n'&&c!='\t'||c==127);}
}
