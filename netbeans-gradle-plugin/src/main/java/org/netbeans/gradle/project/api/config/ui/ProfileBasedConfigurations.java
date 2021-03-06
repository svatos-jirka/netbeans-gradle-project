package org.netbeans.gradle.project.api.config.ui;

import javax.annotation.Nonnull;
import javax.swing.JComponent;
import org.jtrim.utils.ExceptionHelper;
import org.netbeans.api.project.Project;
import org.netbeans.gradle.project.api.config.ProjectSettingsProvider;
import org.netbeans.gradle.project.properties.ProfileBasedCustomizer;
import org.netbeans.gradle.project.properties.ui.ProfileBasedPanel;
import org.netbeans.spi.project.ui.support.ProjectCustomizer;

/**
 * @deprecated Instead of explicitly creating a {@code ProjectCustomizer.CompositeCategoryProvider}, extensions
 *   should now provide instances of {@link ProfileBasedSettingsCategory} on their lookup.
 * <P>
 * Defines utility methods for creating profile based configuration pages.
 */
@Deprecated
public final class ProfileBasedConfigurations {
    /**
     * Creates a {@code ProjectCustomizer.CompositeCategoryProvider} which might
     * be added to the extension's lookup.
     *
     * @param project the project whose properties the created customizer is
     *   used to edit. This argument cannot be {@code null}.
     * @param categoryId the {@code CustomizerCategoryId} defining the page
     *   titles in the project property dialog. This argument cannot be
     *   {@code null}.
     * @param extensionSettings the settings to be adjusted by the customizer.
     *   This argument cannot be {@code null}.
     * @param pageFactory a factory to create the component and to display the
     *   editor of the properties and the logic of setting the properties in
     *   the settings. This argument cannot be {@code null}.
     * @return a {@code ProjectCustomizer.CompositeCategoryProvider} which is
     *   able to adjust the (implicitly) given properties. This argument cannot
     *   be {@code null}.
     *
     * @see org.netbeans.gradle.project.api.entry.GradleProjectExtension2#getExtensionLookup() GradleProjectExtension2.getExtensionLookup
     */
    @Nonnull
    public static ProjectCustomizer.CompositeCategoryProvider createProfileBasedCustomizer(
            @Nonnull final Project project,
            @Nonnull final CustomizerCategoryId categoryId,
            @Nonnull final ProjectSettingsProvider.ExtensionSettings extensionSettings,
            @Nonnull final ProfileBasedProjectSettingsPageFactory pageFactory) {

        ExceptionHelper.checkNotNullArgument(project, "project");
        ExceptionHelper.checkNotNullArgument(categoryId, "categoryId");
        ExceptionHelper.checkNotNullArgument(extensionSettings, "extensionSettings");
        ExceptionHelper.checkNotNullArgument(pageFactory, "pageFactory");

        ProfileBasedCustomizer.PanelFactory panelFactory = new ProfileBasedCustomizer.PanelFactory() {
            @Override
            public ProfileBasedPanel createPanel() {
                ProfileBasedProjectSettingsPage settingsPage = pageFactory.createSettingsPage();
                JComponent customPanel = settingsPage.getSettingsPanel();
                ProfileValuesEditorFactory editorFactory = settingsPage.getEditorFactory();
                return ProfileBasedPanel.createPanel(project, extensionSettings, customPanel, editorFactory);
            }
        };

        return new ProfileBasedCustomizer(categoryId.getCategoryName(), categoryId.getDisplayName(), panelFactory);
    }

    private ProfileBasedConfigurations() {
        throw new AssertionError();
    }
}
