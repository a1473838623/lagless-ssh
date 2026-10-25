package dev.lagless.desktop;

import com.jcraft.jsch.ChannelSftp;
import dev.lagless.I18n;
import dev.lagless.SshConnection;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shells that keep running on the server after Lagless closes, kept by tmux.
 * While no Lagless window is attached, tmux pipes the pane's raw output to a
 * file on the server; the next attach imports it into the local logs, full
 * screen programs included.
 */
final class BackgroundSessions {
    private BackgroundSessions(){}
    static final Pattern NAME=Pattern.compile("[A-Za-z0-9_-]{1,80}");
    /** Read-only facts about the server and its tmux, logged when a background session will not stay up. */
    static final String DIAGNOSTICS="uname -sr 2>&1; (. /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\"); tmux -V 2>&1; echo \"TERM=$TERM SHELL=$SHELL\"; "+
            "tmux has-session -t =NAME 2>&1 && echo session-alive; tmux ls 2>&1 | head -5; ls -ld \"${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)\" 2>&1; "+
            "grep -hs '^ *KillUserProcesses' /etc/systemd/logind.conf /etc/systemd/logind.conf.d/*.conf; loginctl show-user \"$(id -un)\" -p Linger 2>/dev/null; "+
            "systemd-run --user --scope --quiet true >/dev/null 2>&1 && echo user-scope-ok || echo user-scope-unavailable; tail -n 5 \"$HOME/.tmux.conf\" 2>/dev/null | sed 's/^/tmux.conf: /'";
    /** Server folder for the recordings, relative to the login directory. */
    static final String FOLDER=".lagless/background";
    static final String CHECK="command -v tmux >/dev/null 2>&1 && echo \"tmux=$(tmux -V 2>/dev/null)\"; "+
            "for m in apt-get dnf yum zypper pacman apk brew; do if command -v $m >/dev/null 2>&1; then echo pm=$m; break; fi; done; echo uid=$(id -u); "+
            // An installed tmux can still fail to start, e.g. when the server is out of memory. Start a
            // throwaway session on a private socket, see that it stays up, and end it again.
            "if command -v tmux >/dev/null 2>&1; then p=lagless-probe-$$; e=$(tmux -L $p new-session -d -s probe 2>&1); sleep 0.5; "+
            "if tmux -L $p has-session -t probe 2>/dev/null; then echo probe=ok; else echo \"probe=failed ${e:-the tmux server exited right after starting}\" | head -n 1; fi; "+
            "tmux -L $p kill-server 2>/dev/null; rm -f \"${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)/$p\"; fi; "+
            "awk '/^MemAvailable:/{print \"memavail=\"$2} /^SwapTotal:/{print \"swap=\"$2}' /proc/meminfo 2>/dev/null";
    private static final long IMPORT_LIMIT=32L*1024*1024;

    /** {@code starts} is false when tmux is installed but a test session would not stay up; memory figures are in KiB, -1 when unknown. */
    record Check(boolean tmux,String version,String packageManager,boolean root,boolean starts,String failure,long memAvailable,long swap){}
    record Session(String name,long created,boolean attached,int windows){}
    record Prepared(String pipe,List<String> recordings){}

    static Check parseCheck(String output){
        String version=null,pm=null,probe=null;boolean root=false;long mem=-1,swap=-1;
        for(String line:output.split("\\R")){
            if(line.startsWith("tmux="))version=line.substring(5).trim();
            else if(line.startsWith("pm="))pm=line.substring(3).trim();
            else if(line.startsWith("uid="))root=line.substring(4).trim().equals("0");
            else if(line.startsWith("probe="))probe=line.substring(6).trim();
            else if(line.startsWith("memavail="))mem=number(line.substring(9));
            else if(line.startsWith("swap="))swap=number(line.substring(5));
        }
        // No probe line (an older shell or a timeout) is not evidence of failure.
        boolean starts=probe==null||probe.equals("ok");
        String failure=starts?"":probe.replaceFirst("^failed\\s*","");
        return new Check(version!=null,version==null?"":version,pm==null?"":pm,root,starts,failure,mem,swap);
    }
    /** The usual install command for the server's package manager, or "" when none was found. */
    static String installCommand(Check check){
        String sudo=check.root()?"":"sudo ";
        return switch(check.packageManager()){
            case "apt-get"->sudo+"apt-get update && "+sudo+"apt-get install -y tmux";
            case "dnf"->sudo+"dnf install -y tmux";
            case "yum"->sudo+"yum install -y tmux";
            case "zypper"->sudo+"zypper install -y tmux";
            case "pacman"->sudo+"pacman -S --noconfirm tmux";
            case "apk"->sudo+"apk add tmux";
            case "brew"->"brew install tmux";
            default->"";
        };
    }
    static String listCommand(){
        return "tmux list-sessions -F '#{session_name}|#{@lagless}|#{session_created}|#{session_attached}|#{session_windows}' 2>/dev/null; "+
                "for d in \"$HOME/"+FOLDER+"\"/*/; do [ -d \"$d\" ] && echo \"dir|$(basename \"$d\")\"; done 2>/dev/null";
    }
    /** Sessions Lagless created: marked with the @lagless option, or with a recording folder. */
    static List<Session> parseList(String output){
        Set<String> folders=new HashSet<>();List<String[]> rows=new ArrayList<>();
        for(String line:output.split("\\R")){
            String[] parts=line.split("\\|",-1);
            if(parts.length==2&&parts[0].equals("dir"))folders.add(parts[1]);
            else if(parts.length==5&&NAME.matcher(parts[0]).matches())rows.add(parts);
        }
        List<Session> sessions=new ArrayList<>();
        for(String[] p:rows)if(p[1].equals("1")||folders.contains(p[0]))sessions.add(new Session(p[0],number(p[2]),number(p[3])>0,(int)number(p[4])));
        sessions.sort(Comparator.comparingLong(Session::created).reversed());
        return sessions;
    }
    private static long number(String text){try{return Long.parseLong(text.trim());}catch(NumberFormatException e){return 0;}}
    static String defaultName(String profile,LocalDateTime now){
        String base=profile.replaceAll("[^A-Za-z0-9_-]+","-").replaceAll("^-+|-+$","");
        if(base.isEmpty()||base.length()>40)base="lagless";
        return base+"-"+now.format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
    }
    /**
     * Picks the next recording file and lists earlier ones for import. It does
     * not touch tmux: the session is created by the terminal channel itself,
     * as when a user types tmux, because a server started from a command
     * channel without a terminal can be cleaned up with that channel on some
     * systemd servers ("server exited unexpectedly").
     */
    static String prepareCommand(String name){
        String dir="\"$HOME/"+FOLDER+"/"+name+"\"";
        return "command -v tmux >/dev/null 2>&1 || { echo no-tmux; exit 0; }; "+
                "mkdir -p "+dir+" && chmod 700 \"$HOME/"+FOLDER+"\" "+dir+"; "+
                "n="+dir.substring(0,dir.length()-1)+"/$(date +%s)-$$.log\"; echo \"pipe|$n\"; "+
                "for f in "+dir+"/*.log; do [ -f \"$f\" ] && [ \"$f\" != \"$n\" ] && echo \"old|$f\"; done";
    }
    /** Once the attached terminal has created the session: mark it and record its output to {@code pipe}. */
    static String recordCommand(String name,String pipe){
        String file="'"+pipe.replace("'","")+"'";
        return "i=0; until tmux has-session -t ="+name+" 2>/dev/null; do i=$((i+1)); [ $i -gt 100 ] && { echo missing; exit 0; }; sleep 0.1; done; "+
                "tmux set-option -t ="+name+" @lagless 1 >/dev/null 2>&1; : >> "+file+"; "+
                "tmux pipe-pane -t ="+name+": \"exec cat >> "+file+"\" && echo recording";
    }
    static Prepared parsePrepared(String output){
        String pipe=null;List<String> old=new ArrayList<>();
        for(String line:output.split("\\R")){
            if(line.startsWith("pipe|"))pipe=line.substring(5);
            else if(line.startsWith("old|"))old.add(line.substring(4));
        }
        Collections.sort(old);
        return new Prepared(pipe,old);
    }
    /** Run before a clean detach: what Lagless showed is already logged locally, so only later output is kept. */
    static String detachCommand(String name,String pipe){
        String dir="\"$HOME/"+FOLDER+"/"+name+"\"";
        return "n="+dir.substring(0,dir.length()-1)+"/$(date +%s)-$$.log\"; : > \"$n\"; tmux pipe-pane -t ="+name+": \"exec cat >> '$n'\" && rm -f '"+pipe.replace("'","")+"'";
    }

    /** Copies one server recording into a new local log, through the same transcript as live output. */
    static Path importRecording(Path home,String profile,String name,InputStream raw,long skipped,int columns,int rows,boolean full)throws IOException{
        SessionJournal journal=new SessionJournal(home,profile+"-background-"+name);
        try(Reader in=new InputStreamReader(raw,StandardCharsets.UTF_8)){
            SessionTranscript transcript=new SessionTranscript(journal,()->false);
            try{
                transcript.setFull(full);
                journal.append(I18n.t("[后台会话 ","[Background session ")+name+I18n.t(" · 服务器在 Lagless 未连接时记录的输出（含全屏程序）"," · output the server recorded while Lagless was not attached, full-screen programs included")
                        +(skipped>0?I18n.t(" · 已省略较早的 "," · the earliest ")+skipped+I18n.t(" 字节"," bytes are omitted"):"")+I18n.t(" · 若连接曾意外中断，开头可能重复已显示过的内容]\n"," · after an unexpected disconnect the start may repeat output already shown]\n"));
                char[] buffer=new char[8192];int n;
                while((n=in.read(buffer))>0)transcript.append(new String(buffer,0,n),columns,rows);
            }finally{transcript.close();}
        }finally{journal.close();}
        return journal.path();
    }
    /** Downloads, imports and then deletes the recordings left from earlier attaches. Never throws. */
    static void importRecordings(SessionPane pane,SshConnection connection,String name,List<String> recordings){
        if(recordings.isEmpty())return;
        ChannelSftp sftp=null;int imported=0;
        try{
            sftp=connection.openSftp();
            for(String file:recordings){
                long size=sftp.lstat(file).getSize();
                if(size>0){
                    long skip=Math.max(0,size-IMPORT_LIMIT);
                    int[] grid=pane.view.gridSize();Path saved;
                    try(InputStream in=sftp.get(file,null,skip)){saved=importRecording(pane.app.home,pane.profile.name(),name,in,skip,grid[0],grid[1],Boolean.parseBoolean(pane.app.preferences.get("record-fullscreen","true")));}
                    pane.app.events.operation(pane.profile.name(),I18n.t("已导入后台会话记录：","Imported background session recording: ")+name+" · "+size+I18n.t(" 字节"," bytes")+" → "+saved.getFileName());
                    imported++;
                }
                sftp.rm(file);
            }
            if(imported>0)pane.notice(I18n.t("已把后台运行期间的输出导入「全量输入输出历史」。","Output from while the session ran in the background was imported into Full input/output history."));
        }catch(Exception error){
            pane.app.events.operation(pane.profile.name(),I18n.t("后台会话记录导入失败：","Importing the background session recording failed: ")+error.getMessage());
            pane.notice(I18n.t("后台会话记录导入失败，服务器上的记录已保留：","Importing the background recording failed; it stays on the server: ")+error.getMessage());
        }finally{if(sftp!=null)sftp.disconnect();}
    }

    /** Session ▸ Background sessions: checks for tmux with permission, then creates or reconnects. */
    static void show(SessionPane pane){
        SshConnection connection;
        try{connection=pane.requireConnection();}catch(IllegalStateException error){JOptionPane.showMessageDialog(pane,I18n.t("请先连接到服务器，再管理后台会话。","Connect to the server first, then manage background sessions."),I18n.t("后台会话","Background sessions"),JOptionPane.INFORMATION_MESSAGE);return;}
        if(!allowCheck(pane))return;
        pane.app.background(pane,()->{String out=connection.exec(CHECK,15_000);if(out==null)throw new IOException(I18n.t("无法在服务器上执行检查命令","Cannot run the check command on the server"));return parseCheck(out);},check->{
            if(!check.tmux()){offerInstall(pane,check);return;}
            if(!check.starts()){explainFailedStart(pane,check);return;}
            pane.app.background(pane,()->Objects.requireNonNullElse(connection.exec(listCommand(),15_000),""),out->manage(pane,connection,check,parseList(out)));
        });
    }
    // The check now starts a throwaway tmux session, so it is asked for again rather than reusing "read-only" consent.
    private static String allowKey(SessionPane pane){return "background-check2-"+pane.profile.id();}
    /** tmux is installed but cannot keep a session up: say why instead of failing on every attach. */
    private static void explainFailedStart(SessionPane pane,Check check){
        pane.app.events.operation(pane.profile.name(),I18n.t("服务器上的 tmux 无法启动会话：","tmux on the server cannot start a session: ")+check.failure()+" · memavail="+check.memAvailable()+"KiB swap="+check.swap()+"KiB");
        StringBuilder text=new StringBuilder(I18n.t("服务器上已安装 ","tmux ")).append(check.version()).append(I18n.t("，但测试会话无法保持运行：\n",", is installed on the server, but a test session would not stay up:\n")).append(check.failure());
        boolean lowMemory=check.memAvailable()>=0&&check.memAvailable()<256*1024;
        if(check.swap()==0||lowMemory){
            text.append(I18n.t("\n\n服务器可用内存约 ","\n\nThe server has about ")).append(check.memAvailable()<0?"?":check.memAvailable()/1024).append(I18n.t(" MB，交换空间 "," MB of memory available and ")).append(check.swap()<0?"?":check.swap()/1024).append(I18n.t(" MB。内存不足时，新进程会启动失败或被系统结束。可以添加交换空间（以 root 运行）：\n"," MB of swap. When memory runs out, new processes fail to start or are killed. To add swap (as root):\n"))
                .append("fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile\necho '/swapfile none swap sw 0 0' >> /etc/fstab");
        }else text.append(I18n.t("\n\n可在终端运行 tmux new -s test 查看详细原因；也可用 tmux -f /dev/null new -s test 排除 ~/.tmux.conf 的影响。","\n\nRun tmux new -s test in the terminal to see more; tmux -f /dev/null new -s test rules out ~/.tmux.conf."));
        JTextArea area=DesktopApp.textArea();area.setText(text.toString());area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setColumns(60);area.setRows(9);
        JOptionPane.showMessageDialog(pane,new JScrollPane(area),I18n.t("tmux 无法在服务器上启动","tmux cannot start on the server"),JOptionPane.WARNING_MESSAGE);
    }
    static boolean checkAllowed(SessionPane pane){return Boolean.parseBoolean(pane.app.preferences.get(allowKey(pane),"false"));}
    private static boolean allowCheck(SessionPane pane){
        if(checkAllowed(pane))return true;
        JTextArea command=DesktopApp.textArea();command.setText(CHECK);command.setEditable(false);command.setLineWrap(true);command.setRows(7);command.setColumns(64);
        JCheckBox always=new JCheckBox(I18n.t("以后对此服务器不再询问","Don't ask again for this server"));
        Object[] content={I18n.t("后台会话由服务器上的 tmux 保持运行。Lagless 将在 ","Background sessions are kept running by tmux on the server. Lagless will run this check on ")+pane.profile.name()+" ("+pane.profile.host()+I18n.t(") 上运行以下检查：查看是否已安装 tmux、可用的软件包管理器和内存，并在独立的套接字上启动一个临时 tmux 会话、确认其能保持运行后立即结束。不会修改其他内容：",") to see whether tmux is installed, which package manager is available and how much memory is free. It also starts a throwaway tmux session on a private socket, confirms it stays up, and ends it at once. Nothing else is changed:"),new JScrollPane(command),always};
        int answer=JOptionPane.showConfirmDialog(pane,content,I18n.t("允许检查服务器？","Allow the server check?"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE);
        if(answer!=JOptionPane.OK_OPTION)return false;
        if(always.isSelected())pane.app.preferences.set(allowKey(pane),"true");
        pane.app.events.operation(pane.profile.name(),I18n.t("允许后台会话检查命令","Allowed the background session check command"));
        return true;
    }
    private static void offerInstall(SessionPane pane,Check check){
        String suggested=installCommand(check);
        if(suggested.isEmpty()){JOptionPane.showMessageDialog(pane,I18n.t("服务器上没有 tmux，也没有找到已知的软件包管理器。请让管理员安装 tmux 后再试。","tmux is not on the server and no known package manager was found. Ask an administrator to install tmux, then try again."),I18n.t("需要 tmux","tmux needed"),JOptionPane.WARNING_MESSAGE);return;}
        JTextField command=DesktopApp.field(suggested);command.setColumns(48);
        Object[] content={I18n.t("服务器上没有 tmux，后台会话需要它。是否在服务器上安装？\n安装命令将在当前终端中运行，需要时可在终端输入 sudo 密码；可先修改命令：","Background sessions need tmux, which is not on the server. Install it on the server?\nThe command runs in the current terminal, where you can type a sudo password if asked. You can edit it first:"),command};
        if(JOptionPane.showConfirmDialog(pane,content,I18n.t("在服务器上安装 tmux？","Install tmux on the server?"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE)!=JOptionPane.OK_OPTION)return;
        String line=command.getText().trim();if(line.isEmpty())return;
        if(!pane.canSend()){JOptionPane.showMessageDialog(pane,I18n.t("终端当前不在 shell 提示符（例如正在运行程序）。请先退出该程序，再重试安装。","The terminal is not at a shell prompt (a program is running, for example). Leave that program first, then try the installation again."),I18n.t("在服务器上安装 tmux？","Install tmux on the server?"),JOptionPane.WARNING_MESSAGE);return;}
        pane.app.events.operation(pane.profile.name(),I18n.t("用户确认安装 tmux：","User confirmed installing tmux: ")+line);
        if(pane.sendApprovedCommand(line))pane.notice(I18n.t("安装命令已在终端运行；完成后再次打开「后台会话」。","The install command is running in the terminal; open Background sessions again when it finishes."));
    }
    private static void manage(SessionPane pane,SshConnection connection,Check check,List<Session> sessions){
        JDialog dialog=new JDialog(DesktopApp.ownerWindow(pane),pane.profile.name()+I18n.t(" · 后台会话"," · Background sessions"),Dialog.ModalityType.APPLICATION_MODAL);
        JPanel root=new JPanel(new BorderLayout(0,12));root.setBorder(new EmptyBorder(16,18,14,18));
        JLabel intro=new JLabel(I18n.t("<html>后台会话在服务器上持续运行（",
                "<html>Background sessions keep running on the server (")+check.version()+I18n.t("），关闭 Lagless 后也不会结束。<br>重新打开 Lagless 后可在此重新连接；未连接期间的输出会在重连时导入本地日志。</html>",
                "), even after Lagless closes.<br>Reconnect here after reopening Lagless; output from while no window was attached is imported into the local logs.</html>"));
        intro.setForeground(DesktopApp.MUTED);root.add(intro,BorderLayout.NORTH);
        DefaultTableModel model=new DefaultTableModel(new Object[]{I18n.t("名称","Name"),I18n.t("创建时间","Created"),I18n.t("状态","State"),I18n.t("窗口","Windows")},0){@Override public boolean isCellEditable(int r,int c){return false;}};
        DateTimeFormatter format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for(Session s:sessions)model.addRow(new Object[]{s.name(),s.created()>0?format.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(s.created()),ZoneId.systemDefault())):"",s.attached()?I18n.t("已有窗口连接","Attached elsewhere"):I18n.t("后台运行中","Running in background"),s.windows()});
        JTable table=new JTable(model);table.setName("background-sessions");table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setRowHeight(26);table.setFillsViewportHeight(true);if(!sessions.isEmpty())table.setRowSelectionInterval(0,0);
        JScrollPane scroll=new JScrollPane(table);scroll.setPreferredSize(new Dimension(560,170));
        JPanel existing=new JPanel(new BorderLayout(0,6));existing.add(new JLabel(sessions.isEmpty()?I18n.t("此服务器上还没有后台会话。","No background sessions on this server yet."):I18n.t("选择一个后台会话重新连接：","Choose a background session to reconnect:")),BorderLayout.NORTH);existing.add(scroll,BorderLayout.CENTER);
        JPanel existingActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
        JButton end=new JButton(I18n.t("结束会话…","End session…"));JButton attach=new JButton(I18n.t("重新连接","Reconnect"));attach.setName("background-reconnect");DesktopApp.primary(attach);
        attach.setEnabled(!sessions.isEmpty());end.setEnabled(!sessions.isEmpty());existingActions.add(end);existingActions.add(attach);existing.add(existingActions,BorderLayout.SOUTH);
        JPanel create=new JPanel(new BorderLayout(8,6));create.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,DesktopApp.LINE),new EmptyBorder(12,0,0,0)));
        JTextField name=DesktopApp.field(defaultName(pane.profile.name(),LocalDateTime.now()));name.setName("background-name");
        JLabel hint=new JLabel(I18n.t("新会话名称（字母、数字、- 和 _，可留用默认名）：","New session name (letters, digits, - and _; the default is fine):"));
        JButton start=new JButton(I18n.t("新建后台会话","New background session"));start.setName("background-create");
        create.add(hint,BorderLayout.NORTH);create.add(name,BorderLayout.CENTER);create.add(start,BorderLayout.EAST);
        JPanel center=new JPanel(new BorderLayout(0,12));center.add(existing,BorderLayout.CENTER);center.add(create,BorderLayout.SOUTH);root.add(center,BorderLayout.CENTER);
        attach.addActionListener(e->{int row=table.getSelectedRow();if(row<0)return;dialog.dispose();open(pane,sessions.get(table.convertRowIndexToModel(row)).name());});
        table.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2&&table.getSelectedRow()>=0)attach.doClick();}});
        start.addActionListener(e->{
            String chosen=name.getText().trim();
            if(!NAME.matcher(chosen).matches()){JOptionPane.showMessageDialog(dialog,I18n.t("名称只能包含字母、数字、- 和 _，最长 80 个字符。","Names may contain only letters, digits, - and _, up to 80 characters."),I18n.t("后台会话","Background sessions"),JOptionPane.WARNING_MESSAGE);return;}
            if(sessions.stream().anyMatch(s->s.name().equals(chosen))&&JOptionPane.showConfirmDialog(dialog,I18n.t("已有同名后台会话，将重新连接到它。继续？","A background session with this name exists; Lagless will reconnect to it. Continue?"),I18n.t("后台会话","Background sessions"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            dialog.dispose();open(pane,chosen);
        });
        end.addActionListener(e->{
            int row=table.getSelectedRow();if(row<0)return;String target=sessions.get(table.convertRowIndexToModel(row)).name();
            if(JOptionPane.showConfirmDialog(dialog,I18n.t("结束后台会话「","End background session \"")+target+I18n.t("」？其中运行的程序会被终止，服务器上未导入的记录也会删除。","\"? Programs running in it are stopped, and recordings not yet imported are deleted from the server."),I18n.t("结束后台会话","End background session"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION)return;
            pane.app.events.operation(pane.profile.name(),I18n.t("结束后台会话：","End background session: ")+target);
            pane.app.background(dialog,()->Objects.requireNonNullElse(connection.exec("tmux kill-session -t ="+target+" 2>&1; rm -rf \"$HOME/"+FOLDER+"/"+target+"\"",15_000),""),out->{
                int index=table.convertRowIndexToModel(row);model.removeRow(index);sessions.remove(index);attach.setEnabled(!sessions.isEmpty());end.setEnabled(!sessions.isEmpty());
            });
        });
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));JButton close=new JButton(I18n.t("关闭","Close"));close.addActionListener(e->dialog.dispose());actions.add(close);root.add(actions,BorderLayout.SOUTH);
        dialog.setContentPane(root);dialog.getRootPane().setDefaultButton(sessions.isEmpty()?start:attach);dialog.pack();dialog.setLocationRelativeTo(pane);dialog.setVisible(true);
    }
    /** Opens (or selects) a tab attached to the named background session. */
    static void open(SessionPane pane,String name){
        JTabbedPane tabs=(JTabbedPane)SwingUtilities.getAncestorOfClass(JTabbedPane.class,pane);if(tabs==null)return;
        for(SessionPane other:pane.app.sessions)if(name.equals(other.background)&&other.profile.id().equals(pane.profile.id())&&tabs.indexOfComponent(other)>=0){tabs.setSelectedComponent(other);return;}
        pane.app.events.operation(pane.profile.name(),I18n.t("打开后台会话：","Open background session: ")+name);
        pane.app.open(tabs,pane.profile,name);
    }
    /** After connecting, mention waiting background sessions when the user already allowed the check. */
    static void remind(SessionPane pane,SshConnection connection){
        if(pane.background!=null||!checkAllowed(pane))return;
        String out=connection.exec(listCommand(),10_000);if(out==null)return;
        int count=(int)parseList(out).stream().filter(s->!s.attached()).count();
        if(count>0)pane.notice(count+I18n.t(" 个后台会话正在此服务器上运行 · 「会话 ▸ 后台会话」可重新连接"," background session(s) running on this server · reconnect from Session ▸ Background sessions"));
    }
}
