package retrofit.forge.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import retrofit.forge.LegacyDownloads;
import retrofit.forge.RetrofitMod;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the player, once per start, before Retrofit downloads anything (see LegacyDownloads): what
 * would be downloaded, from where, how much, and where it goes. Shown over the title screen
 * when the launcher lacks Minecraft 1.7.10 or Forge 10.13.4.1614 (or the mods' dependencies);
 * this session runs without 1.7.10, which loads after a restart.
 */
public final class LegacyDownloadScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_LISTED = 6;
    private static boolean shown;

    private final Screen parent;
    private final LegacyDownloads downloads;
    private MultiLineLabel text = MultiLineLabel.EMPTY;
    private volatile State state = State.ASKING;
    private volatile String status = "";

    private enum State {
        ASKING, DOWNLOADING, DONE, FAILED
    }

    private LegacyDownloadScreen(Screen parent, LegacyDownloads downloads) {
        super(Component.translatable(downloads.files().isEmpty() ? "retrofit.download.title.dependencies" : "retrofit.download.title"));
        this.parent = parent;
        this.downloads = downloads;
    }

    @Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
    public static final class Show {
        private Show() {
        }

        @SubscribeEvent
        public static void onScreenOpening(ScreenEvent.Opening event) {
            LegacyDownloads downloads = RetrofitMod.pendingDownloads();
            if (!shown && downloads != null && event.getNewScreen() instanceof TitleScreen title) {
                shown = true;
                event.setNewScreen(new LegacyDownloadScreen(title, downloads));
            }
        }
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        List<Component> lines = new ArrayList<>();
        int buttonsY = height - 28;
        switch (state) {
            case ASKING -> {
                if (!downloads.files().isEmpty()) {
                    lines.add(Component.translatable("retrofit.download.intro", downloads.size()));
                    // the game and Forge on their own lines; single libraries (logging, ASM...) summed up in one
                    List<Component> items = new ArrayList<>();
                    List<String> libraries = new ArrayList<>();
                    for (var file : downloads.files()) {
                        if (file.what().startsWith("library ") || file.what().startsWith("Apache Commons")) {
                            String name = file.what().replaceFirst("^library ", "");
                            libraries.add(name.contains(":") ? name.substring(name.indexOf(':') + 1) : name);
                        } else {
                            items.add(Component.translatable("retrofit.download.file", file.what(), file.from()));
                        }
                    }
                    if (!libraries.isEmpty()) {
                        items.add(Component.translatable("retrofit.download.libraries", libraries.size(), String.join(", ", libraries)));
                    }
                    listed(lines, items);
                }
                if (!downloads.dependencies().isEmpty()) {
                    lines.add(Component.translatable("retrofit.download.dependencies"));
                    listed(lines, downloads.dependencies().stream().map(dep -> (Component) Component.translatable("retrofit.download.dependency",
                            dep.file(), dep.wantedBy(), dep.url())).toList());
                }
                lines.add(downloads.files().isEmpty() ? Component.translatable("retrofit.download.where.dependencies", downloads.dependencyDir().toString())
                        : Component.translatable("retrofit.download.where", downloads.cacheDir().toString()));
                int w = 120;
                addRenderableWidget(Button.builder(Component.translatable("retrofit.download.yes"), b -> start()).bounds(width / 2 - w * 3 / 2 - 6, buttonsY, w, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("retrofit.download.later"), b -> onClose()).bounds(width / 2 - w / 2, buttonsY, w, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("retrofit.download.never"), b -> never()).bounds(width / 2 + w / 2 + 6, buttonsY, w, 20).build());
            }
            case DOWNLOADING -> lines.add(Component.translatable("retrofit.download.progress", status));
            case DONE -> {
                lines.add(Component.translatable("retrofit.download.done"));
                addRenderableWidget(Button.builder(Component.translatable("retrofit.download.quit"), b -> minecraft.stop()).bounds(width / 2 - 154, buttonsY, 150, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("retrofit.download.back"), b -> onClose()).bounds(width / 2 + 4, buttonsY, 150, 20).build());
            }
            case FAILED -> {
                lines.add(Component.translatable("retrofit.download.failed", status));
                addRenderableWidget(Button.builder(Component.translatable("retrofit.download.retry"), b -> start()).bounds(width / 2 - 154, buttonsY, 150, 20).build());
                addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 4, buttonsY, 150, 20).build());
            }
        }
        net.minecraft.network.chat.MutableComponent all = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getString();
            if (i > 0) {
                all.append(line.startsWith("•") || line.startsWith("...") ? "\n" : "\n\n"); // a blank line between paragraphs
            }
            all.append(lines.get(i));
        }
        text = MultiLineLabel.create(font, all, width - 50);
    }

    private static void listed(List<Component> lines, List<Component> items) {
        lines.addAll(items.subList(0, Math.min(MAX_LISTED, items.size())));
        if (items.size() > MAX_LISTED) {
            lines.add(Component.translatable("retrofit.download.more", items.size() - MAX_LISTED));
        }
    }

    private void start() {
        state = State.DOWNLOADING;
        status = "";
        rebuild();
        Thread thread = new Thread(() -> {
            try {
                downloads.fetch(url -> {
                    status = url;
                    minecraft.execute(this::rebuild);
                });
                state = State.DONE;
                LOGGER.info("Downloaded what 1.7.10 needs; it loads at the next start");
            } catch (Exception e) {
                status = e.getMessage();
                state = State.FAILED;
                LOGGER.error("Retrofit's download failed", e);
            }
            minecraft.execute(this::rebuild);
        }, "Retrofit download");
        thread.setDaemon(true);
        thread.start();
    }

    private void never() {
        try {
            downloads.neverAsk();
        } catch (java.io.IOException e) {
            LOGGER.error("Could not save the answer in retrofit/retrofit.properties", e);
        }
        LOGGER.info("The player chose not to download what 1.7.10 needs; Retrofit will not ask again");
        onClose();
    }

    /** Development (-Dretrofit.downloadTest=true): screenshots the question, answers Download, screenshots the result, quits. */
    private static final boolean TEST = Boolean.getBoolean("retrofit.downloadTest");
    private int ticks;
    private int doneTicks;

    @Override
    public void tick() {
        if (!TEST) {
            return;
        }
        ticks++;
        if (ticks == 100) { // after the loading overlay has faded
            net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, "retrofit-download-ask.png", minecraft.getMainRenderTarget(), m -> { });
        } else if (ticks == 120) {
            LOGGER.info("[DownloadTest] Answering Download");
            start();
        } else if ((state == State.DONE || state == State.FAILED) && ++doneTicks == 20) {
            net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, "retrofit-download-" + (state == State.DONE ? "done" : "failed") + ".png",
                    minecraft.getMainRenderTarget(), m -> { });
            LOGGER.info("[DownloadTest] {}: {}", state == State.DONE ? "PASSED" : "FAILED", status);
        } else if (doneTicks == 40) {
            minecraft.stop();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        text.renderCentered(graphics, width / 2, 36, font.lineHeight + 1, 0xDDDDDD);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return state != State.DOWNLOADING;
    }

    @Override
    public void onClose() {
        if (state != State.DOWNLOADING) {
            Minecraft.getInstance().setScreen(parent);
        }
    }
}
