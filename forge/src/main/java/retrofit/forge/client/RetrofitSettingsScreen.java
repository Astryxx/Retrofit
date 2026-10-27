package retrofit.forge.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import retrofit.forge.LegacyDownloads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Retrofit's own settings (retrofit/retrofit.properties): whether it may download what 1.7.10 needs. */
public final class RetrofitSettingsScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Screen parent;
    private final Path legacyDir = FMLPaths.GAMEDIR.get().resolve("retrofit");
    private MultiLineLabel explanation = MultiLineLabel.EMPTY;

    public RetrofitSettingsScreen(Screen parent) {
        super(Component.translatable("retrofit.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LegacyDownloads.Permission current = LegacyDownloads.permission(legacyDir);
        addRenderableWidget(CycleButton.<LegacyDownloads.Permission>builder(permission ->
                        Component.translatable("retrofit.settings.downloads." + permission.name().toLowerCase(Locale.ROOT)))
                .withValues(LegacyDownloads.Permission.values())
                .withInitialValue(current)
                .create(width / 2 - 155, height / 6, 310, 20, Component.translatable("retrofit.settings.downloads"), (button, permission) -> {
                    try {
                        LegacyDownloads.setPermission(legacyDir, permission);
                    } catch (IOException e) {
                        LOGGER.warn("Could not save Retrofit's settings", e);
                    }
                }));
        explanation = MultiLineLabel.create(font, Component.translatable("retrofit.settings.downloads.explanation"), 310);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, height - 27, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        explanation.renderLeftAligned(graphics, width / 2 - 155, height / 6 + 30, font.lineHeight + 1, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
