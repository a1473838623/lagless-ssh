package dev.lagless.desktop;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Renders the actual Swing workspace with sample data for README media. No SSH connection is made. */
public final class GalleryCapture {
    private static final int WIDTH = 1440, HEIGHT = 900;
    private GalleryCapture() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "docs/media" : args[0]);
        dev.lagless.I18n.setLanguage(dev.lagless.I18n.Language.parse(System.getProperty("lagless.language", "zh")));
        Files.createDirectories(output);
        Path temporary = Files.createTempDirectory("lagless-gallery-");
        System.setProperty("user.home", temporary.toString());
        new DesktopPreferences(temporary.resolve(".lagless")).set("renderer", "compat");
        DesktopApp[] appRef = new DesktopApp[1];
        SessionPane[] paneRef = new SessionPane[1];
        JFrame[] frameRef = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                DesktopApp.theme();
                DesktopApp app = new DesktopApp(temporary, false);
                appRef[0] = app;
                Profile sample = new Profile("demo-prod", "Production · Tokyo", "example.invalid", "deploy", 22, "", "", true, false);
                app.accounts.addElement(sample);
                app.accounts.addElement(new Profile("demo-stage", "Staging · Singapore", "example.invalid", "ops", 2222, "", "", true, false));
                app.accounts.addElement(new Profile("demo-lab", "Development · Local", "localhost", "dev", 22, "", "", true, false));
                JPanel root = app.content(null);
                JFrame frame = new JFrame("Lagless gallery preview");frameRef[0]=frame;
                frame.setContentPane(root);frame.setSize(WIDTH, HEIGHT);frame.setFocusableWindowState(false);frame.setLocation(-10000,-10000);frame.setVisible(true);
                root.setSize(WIDTH, HEIGHT);
                paint(root, output.resolve("welcome.png"));

                JTabbedPane tabs = findTabs(root);
                if (tabs == null) throw new IllegalStateException("Workspace tabs not found");
                SessionPane pane = new SessionPane(app, sample);
                paneRef[0] = pane;
                tabs.addTab(sample.name(), pane);
                tabs.setSelectedComponent(pane);
                pane.view.display("\u001b[32mLagless SSH demo · sample output\u001b[0m\r\n\r\n"
                        + "deploy@example:~$ git status\r\nOn branch main\r\nYour working tree is clean.\r\n\r\n"
                        + "deploy@example:~$ ls -la\r\nREADME.md  src/  docs/\r\n\r\n"
                        + "\u001b[36mLocal editing is ready · typing does not wait for network\u001b[0m\r\n"
                        + "deploy@example:~$ ");
                pane.draft.setText("git status");
                setPreviewStatus(pane);
                paint(root, output.resolve("terminal.png"));

                pane.setEditorPlacement("right");
                pane.draft.setText("# Edit locally before sending\ngit status\nsystemctl status nginx");
                setToolWidth(pane);
                paint(root, output.resolve("local-editor.png"));

                pane.mirror.setText("deploy@example:~$ git status\nOn branch main\nYour working tree is clean.\n\n"
                        + "deploy@example:~$ ls -la\nREADME.md  src/  docs/\n");
                pane.showTool("mirror");
                setToolWidth(pane);
                paint(root, output.resolve("output-mirror.png"));

                pane.showTool("assistant");
                setToolWidth(pane);
                paint(root, output.resolve("ai-assistant.png"));

                pane.setEditorPlacement("bottom");
                pane.hideTools();
                tabs.setSelectedComponent(pane);
                List<BufferedImage> frames = new java.util.ArrayList<>();
                String command = "git status";
                for (int length = 3; length <= command.length(); length++) {
                    pane.draft.setText(command.substring(0, length));
                    BufferedImage full = snapshot(root);
                    frames.add(full.getSubimage(0, HEIGHT - 145, 1100, 120));
                }
                for (int hold = 0; hold < 5; hold++) frames.add(frames.get(frames.size() - 1));
                writeGif(frames, output.resolve("local-editing.gif"), 180);
                writeGif(List.of(ImageIO.read(output.resolve("terminal.png").toFile()),
                        ImageIO.read(output.resolve("local-editor.png").toFile()),
                        ImageIO.read(output.resolve("output-mirror.png").toFile()),
                        ImageIO.read(output.resolve("ai-assistant.png").toFile())),
                        output.resolve("workspace-tour.gif"), 1600);
                writeSocialCard(output.resolve("local-editor.png"), output.resolve("social-preview.png"));
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                if (paneRef[0] != null) paneRef[0].close();
                if (frameRef[0] != null) frameRef[0].dispose();
                if (appRef[0] != null) appRef[0].close();
            }
        });
    }

    private static void setPreviewStatus(SessionPane pane) {
        for (Component child : ((JPanel) pane.getComponent(0)).getComponents()) {
            if (child instanceof JLabel label && "准备连接…".equals(label.getText())) {
                label.setText("界面演示 · 示例数据");
                break;
            }
        }
    }

    private static JTabbedPane findTabs(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTabbedPane tabs) return tabs;
            if (child instanceof Container nested) {
                JTabbedPane found = findTabs(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void setToolWidth(SessionPane pane) {
        layout(pane);
        JSplitPane split = findSplit(pane);
        if (split != null) split.setDividerLocation(900);
    }

    private static JSplitPane findSplit(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JSplitPane split) return split;
            if (child instanceof Container nested) {
                JSplitPane found = findSplit(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static BufferedImage snapshot(JPanel root) {
        layout(root); layout(root); layout(root);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        root.printAll(graphics);
        graphics.dispose();
        return image;
    }

    private static void paint(JPanel root, Path target) throws Exception {
        ImageIO.write(snapshot(root), "png", target.toFile());
    }

    private static void writeGif(List<BufferedImage> frames, Path target, int delayMs) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < frames.size(); index++) {
                BufferedImage frame = frames.get(index);
                IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), null);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", Integer.toString(Math.max(1, delayMs / 10)));
                control.setAttribute("disposalMethod", "none");
                if (index == 0) {
                    IIOMetadataNode extensions = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[]{1, 0, 0});
                    extensions.appendChild(loop);
                    root.appendChild(extensions);
                }
                metadata.setFromTree(format, root);
                writer.writeToSequence(new javax.imageio.IIOImage(frame, null, metadata), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static void writeSocialCard(Path screenshot, Path target) throws Exception {
        BufferedImage image = new BufferedImage(1280, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setPaint(new GradientPaint(0, 0, new Color(0x11151d), 1280, 640, new Color(0x24263b)));
            g.fillRect(0, 0, 1280, 640);
            g.drawImage(DesktopApp.appIcon(80), 58, 66, null);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            g.setColor(new Color(0xf3f5fa));
            g.drawString("LAGLESS SSH", 160, 119);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 54));
            g.drawString("SSH at your speed.", 58, 250);
            g.setColor(new Color(0xb29fff));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 25));
            g.drawString("Local command editing", 61, 317);
            g.drawString("on slow or unstable links.", 61, 352);
            g.setColor(new Color(0xcbd3e0));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
            g.drawString("Standalone terminal core  •  Desktop workspace", 61, 566);
            BufferedImage screen = ImageIO.read(screenshot.toFile());
            g.setColor(new Color(0x4c4373));
            g.fillRoundRect(620, 140, 622, 394, 18, 18);
            g.drawImage(screen, 631, 151, 600, 375, null);
            g.setColor(new Color(0x9ba9bc));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString("Product UI · sample data", 1050, 552);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", target.toFile());
    }
}
