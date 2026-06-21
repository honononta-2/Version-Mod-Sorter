package vms;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

// クラッシュ通知ダイアログの表示プロセス
// メッセージは標準入力（UTF-8）で受け取る
public final class CrashDialog {
    private CrashDialog() {
    }

    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = System.in.read(chunk)) != -1; ) {
            buf.write(chunk, 0, n);
        }
        String message = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        if (!message.trim().isEmpty()) {
            show(message);
        }
        // SwingのEDTが非デーモンのため、明示終了しないとプロセスが残る
        System.exit(0);
    }

    // 翻訳・問い合わせに貼れるよう、本文は選択可能にしCopyボタンを付ける
    private static void show(final String message) {
        JTextArea area = new JTextArea(message);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFont(UIManager.getFont("Label.font"));

        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(message), null);
            } catch (Exception ignored) {
            }
        });
        JButton ok = new JButton("OK");

        JOptionPane pane = new JOptionPane(area, JOptionPane.WARNING_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, new Object[] {copy, ok});
        JDialog dialog = pane.createDialog("Version Mod Sorter");
        ok.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
        dialog.dispose();
    }
}
