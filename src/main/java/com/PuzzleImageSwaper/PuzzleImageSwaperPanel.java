package com.PuzzleImageSwaper;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.File;

/**
 * Custom plugin side panel (left sidebar button) for PuzzleImageSwaper.
 *
 * Why this exists:
 * - RuneLite's standard config panel can only show basic config items (checkboxes, text fields, dropdowns).
 * - It cannot add custom "Upload image" buttons.
 * - Create a PluginPanel with a Swing button that opens the OS file explorer (JFileChooser).
 *
 * Behavior:
 * - Clicking "Choose image..." opens a file explorer dialog.
 * - When the user selects a file, store the absolute path in RuneLite config:
 *     group: PuzzleImageSwaper
 *     key:   imagePath
 * - The plugin listens for ConfigChanged events and reloads the image automatically.
 */
public class PuzzleImageSwaperPanel extends PluginPanel
{
    /**
     * ConfigManager allows us to read/write RuneLite config values.
     * Use it here to persist the selected file path (imagePath).
     */
    private final ConfigManager configManager;

    /**
     * A small label that shows the currently selected image path (or "none selected").
     * This is purely UI/UX; the plugin itself reads from config.imagePath().
     */
    private final JLabel currentPathLabel = new JLabel();

    public PuzzleImageSwaperPanel(ConfigManager configManager)
    {
        super();
        this.configManager = configManager;

        /**
         * Simple layout:
         * - Put a content panel at the top (NORTH) so it doesn't stretch weirdly.
         * - BorderLayout lets us keep content at the top.
         */
        setLayout(new BorderLayout(0, 8));

        /**
         * GridLayout(0,1) means "one column, as many rows as needed".
         * Adds simple vertical spacing between rows.
         */
        JPanel content = new JPanel(new GridLayout(0, 1, 0, 8));

        /**
         * Main UI action:
         * - A button that opens the OS file picker.
         * TODO: re-adjust panel layout
         */
        JButton chooseButton = new JButton("Choose image...");
        chooseButton.addActionListener(e -> openFileChooser());

        // Add UI elements to panel
        content.add(chooseButton);
        content.add(new JLabel("Current image path:"));
        content.add(currentPathLabel);

        // Place content at the top of the side panel
        add(content, BorderLayout.NORTH);

        // Initialize label from the current stored config value
        refreshCurrentPath();
    }

    /**
     * Reads the stored config value and updates the label.
     * This can be called:
     * - on panel creation
     * - after the user selects a new file
     * - from the plugin when it receives ConfigChanged to keep UI in sync
     */
    public void refreshCurrentPath()
    {
        String path = configManager.getConfiguration("PuzzleImageSwaper", "imagePath");

        if (path == null || path.trim().isEmpty())
        {
            currentPathLabel.setText("(none selected)");
        }
        else
        {
            currentPathLabel.setText(path);
        }
    }

    /**
     * Opens a file chooser dialog and writes the selected image path to config.
     *
     * Notes:
     * - RuneLite UI is Swing-based: Used SwingUtilities.invokeLater to make sure this runs on the Swing EDT.
     * - Filter for common image extensions.
     * - Store the absolute path to avoid ambiguity with relative paths.
     */
    private void openFileChooser()
    {
        SwingUtilities.invokeLater(() ->
        {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select puzzle background image");

            // Filter common image types (user can still switch filters if needed)
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Images (png, jpg, jpeg, gif)",
                    "png", "jpg", "jpeg", "gif"
            ));

            // Show chooser and wait for user action
            int result = chooser.showOpenDialog(this);
            if (result != JFileChooser.APPROVE_OPTION)
            {
                // User cancelled or closed dialog
                return;
            }

            File selected = chooser.getSelectedFile();
            if (selected == null)
            {
                // Defensive: shouldn't happen, but avoids NPE
                return;
            }

            /**
             * Persist the chosen path into RuneLite config.
             *
             * This triggers ConfigChanged, and the plugin will:
             * - reload the image
             * - re-split into tiles
             * - update overlay tiles
             */
            configManager.setConfiguration("PuzzleImageSwaper", "imagePath", selected.getAbsolutePath());

            // Update the label immediately so the panel reflects the change
            refreshCurrentPath();
        });
    }
}