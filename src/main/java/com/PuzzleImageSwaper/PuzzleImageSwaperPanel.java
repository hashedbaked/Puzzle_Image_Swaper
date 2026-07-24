package com.PuzzleImageSwaper;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;

/**
 * Custom plugin side panel for PuzzleImageSwaper.
 */
public class PuzzleImageSwaperPanel extends PluginPanel
{
    private static final int PANEL_PAD = 6;
    private static final int GAP_XS = 2;
    private static final int GAP_SM = 4;
    private static final int GAP_MD = 6;
    private static final int PATH_BOX_HEIGHT = 42;
    private static final int HINT_BOX_HEIGHT = 38;

    private final ConfigManager configManager;

    private final JCheckBox enabledCheck = new JCheckBox("Enable plugin");
    private final JCheckBox useGlobalCheck = new JCheckBox("Use one global image");

    private final JTextArea modeHintArea = createInfoArea();

    private final JButton chooseGlobalButton = new JButton("Choose global image...");
    private final JTextArea globalPathArea = createPathArea();

    private final JButton chooseTreeButton = new JButton("Choose Tree image...");
    private final JTextArea treePathArea = createPathArea();

    private final JButton chooseTrollButton = new JButton("Choose Troll image...");
    private final JTextArea trollPathArea = createPathArea();

    private final JButton chooseCastleButton = new JButton("Choose Castle image...");
    private final JTextArea castlePathArea = createPathArea();

    public PuzzleImageSwaperPanel(ConfigManager configManager)
    {
        this.configManager = configManager;
        setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(PANEL_PAD, PANEL_PAD, PANEL_PAD, PANEL_PAD));

        enabledCheck.setAlignmentX(LEFT_ALIGNMENT);
        useGlobalCheck.setAlignmentX(LEFT_ALIGNMENT);

        enabledCheck.addActionListener(e ->
                configManager.setConfiguration(
                        PuzzleProfileImageKeys.CONFIG_GROUP,
                        PuzzleProfileImageKeys.KEY_PLUGIN_ENABLED,
                        Boolean.toString(enabledCheck.isSelected())
                )
        );

        useGlobalCheck.addActionListener(e ->
        {
            configManager.setConfiguration(
                    PuzzleProfileImageKeys.CONFIG_GROUP,
                    PuzzleProfileImageKeys.KEY_USE_GLOBAL_IMAGE,
                    Boolean.toString(useGlobalCheck.isSelected())
            );
            refreshUiState();
        });

        chooseGlobalButton.addActionListener(e -> chooseAndSave(PuzzleProfileImageKeys.KEY_GLOBAL_IMAGE_PATH));
        chooseTreeButton.addActionListener(e -> chooseAndSave("treeImagePath"));
        chooseTrollButton.addActionListener(e -> chooseAndSave("trollImagePath"));
        chooseCastleButton.addActionListener(e -> chooseAndSave("castleImagePath"));

        // Top controls
        content.add(enabledCheck);
        content.add(Box.createVerticalStrut(GAP_SM));
        content.add(useGlobalCheck);
        content.add(Box.createVerticalStrut(GAP_SM));
        content.add(fullWidth(wrapArea(modeHintArea, HINT_BOX_HEIGHT)));

        content.add(Box.createVerticalStrut(GAP_MD));
        content.add(fullWidth(createSectionSeparator()));
        content.add(Box.createVerticalStrut(GAP_SM));

        // Global section
        content.add(leftLabel("Global image:"));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(chooseGlobalButton));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(wrapArea(globalPathArea, PATH_BOX_HEIGHT)));

        content.add(Box.createVerticalStrut(GAP_MD));
        content.add(fullWidth(createSectionSeparator()));
        content.add(Box.createVerticalStrut(GAP_SM));

        // Per-puzzle section
        content.add(leftLabel("Per-puzzle images:"));
        content.add(Box.createVerticalStrut(GAP_SM));

        content.add(leftLabel("Tree puzzle:"));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(chooseTreeButton));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(wrapArea(treePathArea, PATH_BOX_HEIGHT)));

        content.add(Box.createVerticalStrut(GAP_SM));

        content.add(leftLabel("Troll puzzle:"));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(chooseTrollButton));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(wrapArea(trollPathArea, PATH_BOX_HEIGHT)));

        content.add(leftLabel("Castle puzzle:"));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(chooseCastleButton));
        content.add(Box.createVerticalStrut(GAP_XS));
        content.add(fullWidth(wrapArea(castlePathArea, PATH_BOX_HEIGHT)));

        content.add(Box.createVerticalGlue());

        add(content, BorderLayout.CENTER);

        refreshCurrentPath();
        refreshUiState();
    }

    public void refreshCurrentPath()
    {
        boolean enabled = getBool(PuzzleProfileImageKeys.KEY_PLUGIN_ENABLED, true);
        boolean useGlobal = getBool(PuzzleProfileImageKeys.KEY_USE_GLOBAL_IMAGE, true);

        enabledCheck.setSelected(enabled);
        useGlobalCheck.setSelected(useGlobal);

        setPathArea(globalPathArea, getString(PuzzleProfileImageKeys.KEY_GLOBAL_IMAGE_PATH));
        setPathArea(treePathArea, getString("treeImagePath"));
        setPathArea(trollPathArea, getString("trollImagePath"));
        setPathArea(castlePathArea, getString("castleImagePath"));
    }

    private void refreshUiState()
    {
        boolean useGlobal = useGlobalCheck.isSelected();

        modeHintArea.setText(useGlobal
                ? "Using global image for all puzzle profiles."
                : "Using per-puzzle images (falls back to global if missing).");

        chooseGlobalButton.setEnabled(useGlobal);
        globalPathArea.setEnabled(useGlobal);

        chooseTreeButton.setEnabled(!useGlobal);
        treePathArea.setEnabled(!useGlobal);

        chooseTrollButton.setEnabled(!useGlobal);
        trollPathArea.setEnabled(!useGlobal);

        chooseCastleButton.setEnabled(!useGlobal);
        castlePathArea.setEnabled(!useGlobal);
    }

    private void chooseAndSave(String configKey)
    {
        SwingUtilities.invokeLater(() ->
        {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select puzzle image");
            chooser.setFileFilter(new FileNameExtensionFilter("Images (png, jpg, jpeg, gif)", "png", "jpg", "jpeg", "gif"));

            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            {
                return;
            }

            File selected = chooser.getSelectedFile();
            if (selected == null)
            {
                return;
            }

            configManager.setConfiguration(
                    PuzzleProfileImageKeys.CONFIG_GROUP,
                    configKey,
                    selected.getAbsolutePath()
            );

            refreshCurrentPath();
            refreshUiState();
        });
    }

    private boolean getBool(String key, boolean def)
    {
        String v = configManager.getConfiguration(PuzzleProfileImageKeys.CONFIG_GROUP, key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private String getString(String key)
    {
        String v = configManager.getConfiguration(PuzzleProfileImageKeys.CONFIG_GROUP, key);
        return v == null ? "" : v;
    }

    private void setPathArea(JTextArea area, String fullPath)
    {
        if (isBlank(fullPath))
        {
            area.setText("(none selected)");
            area.setToolTipText(null);
            return;
        }

        String trimmed = fullPath.trim();
        area.setText(trimmed);
        area.setCaretPosition(0);
        area.setToolTipText(trimmed);
    }

    private static JTextArea createPathArea()
    {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(2);
        area.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        area.setOpaque(false);
        return area;
    }

    private static JTextArea createInfoArea()
    {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(2);
        area.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        area.setOpaque(false);
        return area;
    }

    private static JScrollPane wrapArea(JTextArea area, int preferredHeight)
    {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createEtchedBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(new Dimension(10, preferredHeight));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferredHeight));
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        return scroll;
    }

    private static JSeparator createSectionSeparator()
    {
        JSeparator sep = new JSeparator(JSeparator.HORIZONTAL);
        sep.setAlignmentX(LEFT_ALIGNMENT);
        return sep;
    }

    private static JLabel leftLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static javax.swing.JComponent fullWidth(javax.swing.JComponent c)
    {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getMaximumSize().height));
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }
}