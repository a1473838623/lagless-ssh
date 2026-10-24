package dev.lagless.desktop;

import javax.swing.*;
import java.awt.Component;

/** Real line breaks are reviewed at the boundary where text enters the terminal. */
final class MultilineInput {
    private MultilineInput(){}
    static String normalize(String text){return text.replace("\r\n","\n").replace('\r','\n');}
    static String prepare(Component parent,String original){
        String text=normalize(original);
        if(text.indexOf('\n')<0)return text;
        Object[] choices={"合并为一行（软换行）","保留换行","取消"};
        int choice=JOptionPane.showOptionDialog(parent,
                "内容包含真实换行符，是否合并为一行？\n合并：将换行替换为空格，终端按宽度自动折行。\n保留：发送完整多行内容，换行可能触发远端执行。",
                "发送前检查换行",JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,choices,choices[0]);
        return choice==0?text.replace('\n',' '):choice==1?text:null;
    }
    static boolean unsupportedControls(String text){return text.chars().anyMatch(c->c<32&&c!='\n'&&c!='\t'||c==127);}
}
