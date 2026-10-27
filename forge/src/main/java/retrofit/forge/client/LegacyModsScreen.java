package retrofit.forge.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringUtil;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.widget.ScrollPanel;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.ForgeI18n;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import retrofit.forge.RetrofitMod;
import retrofit.forge.world.LegacyBridge;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * The 1.7.10 mods, on a page of their own laid out as Forge's mod list: each mod with what its
 * mcmod.info says, why it didn't load if it didn't, and its configuration (the mod's own screen,
 * or FML's over its .cfg files). Opened from the options screen, which also leads to Retrofit's
 * own settings.
 */
public final class LegacyModsScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PADDING = 6;

    private enum SortType {
        NORMAL, A_TO_Z, Z_TO_A;

        Button button;

        Component text() {
            return Component.translatable("fml.menu.mods." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** One 1.7.10 mod, as the legacy side describes it (see ClientMods.legacyMods). */
    private record LegacyMod(String id, String name, String version, String authors, String url, String credits, String description,
                             String logo, String jar, String leftOut, boolean configurable) {
        static LegacyMod of(Object[] row) {
            return new LegacyMod((String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
                    (String) row[6], (String) row[7], (String) row[8], (String) row[9], (Boolean) row[10]);
        }
    }

    private final Screen parent;
    private final List<LegacyMod> mods = new ArrayList<>();
    private ModList modList;
    private InfoPanel modInfo;
    private ModList.Entry selected;
    private int listWidth;
    private Button configButton;
    private EditBox search;
    private String lastFilterText = "";
    private SortType sortType = SortType.NORMAL;
    /** A configuration screen was opened: most 1.7.10 mods read their configs only at startup. */
    private boolean configured;
    private ResourceLocation logo;
    private DynamicTexture logoTexture;

    public LegacyModsScreen(Screen parent) {
        super(Component.translatable("retrofit.mods.title"));
        this.parent = parent;
        if (LegacyBridge.isReady()) {
            for (Object[] row : LegacyBridge.<Object[][]>call("legacyMods")) {
                mods.add(LegacyMod.of(row));
            }
        }
    }

    /** The options screen leads here, and to Retrofit's own settings. */
    @Mod.EventBusSubscriber(modid = RetrofitMod.MOD_ID, value = Dist.CLIENT)
    public static final class Show {
        private Show() {
        }

        @SubscribeEvent
        public static void onInit(ScreenEvent.Init.Post event) {
            if (event.getScreen() instanceof OptionsScreen options) {
                event.addListener(Button.builder(Component.translatable("retrofit.mods.button"),
                                button -> Minecraft.getInstance().setScreen(new LegacyModsScreen(options)))
                        .bounds(PADDING, PADDING, 100, 20).build());
            }
        }
    }

    @Override
    protected void init() {
        listWidth = 100;
        for (LegacyMod mod : mods) {
            listWidth = Math.max(listWidth, font.width(mod.name()) + 10);
            listWidth = Math.max(listWidth, font.width(mod.version()) + 5);
        }
        listWidth = Math.max(Math.min(listWidth, width / 3), 100);
        int sorts = SortType.values().length;
        listWidth += listWidth % sorts != 0 ? sorts - listWidth % sorts : 0;

        int modInfoWidth = width - listWidth - PADDING * 3;
        int doneButtonWidth = Math.min(modInfoWidth, 200);
        int y = height - 20 - PADDING;
        int fullButtonHeight = PADDING + 20 + PADDING;
        search = new EditBox(font, PADDING + 1, y - 20 - PADDING - 14 - PADDING, listWidth - 2, 14, Component.translatable("fml.menu.mods.search"));
        modList = new ModList(listWidth, fullButtonHeight, search.getY() - font.lineHeight - PADDING);
        modList.setLeftPos(PADDING);
        modInfo = new InfoPanel(modInfoWidth, height - PADDING - fullButtonHeight * 2, PADDING);
        // as Forge's mod list: the list first, as it draws its edges over the column below it
        addRenderableWidget(modList);
        addRenderableWidget(modInfo);
        addRenderableWidget(search);
        search.setFocused(false);
        search.setCanLoseFocus(true);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds((listWidth + PADDING + width - doneButtonWidth) / 2, y, doneButtonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("retrofit.mods.openconfigfolder"),
                        button -> Util.getPlatform().openFile(FMLPaths.GAMEDIR.get().resolve("retrofit").resolve("config").toFile()))
                .bounds(PADDING, y, listWidth, 20).build());
        y -= 20 + PADDING;
        configButton = addRenderableWidget(Button.builder(Component.translatable("fml.menu.mods.config"), button -> openConfig())
                .bounds(PADDING, y, listWidth, 20).build());
        configButton.active = false;
        addRenderableWidget(Button.builder(Component.translatable("retrofit.settings.button"),
                        button -> minecraft.setScreen(new RetrofitSettingsScreen(this)))
                .bounds((listWidth + PADDING + width - doneButtonWidth) / 2, y, doneButtonWidth, 20).build());

        int buttonWidth = listWidth / sorts;
        int x = PADDING;
        for (SortType sort : SortType.values()) {
            sort.button = addRenderableWidget(Button.builder(sort.text(), button -> resort(sort)).bounds(x, PADDING, buttonWidth - 2, 20).build());
            x += buttonWidth;
        }
        resort(sortType);
        if (selected != null) {
            select(modList.children().stream().filter(entry -> entry.mod == selected.mod).findFirst().orElse(null));
        }
    }

    @Override
    public void tick() {
        search.tick();
        if (!search.getValue().equals(lastFilterText)) {
            refresh();
        }
    }

    private void resort(SortType sort) {
        sortType = sort;
        for (SortType type : SortType.values()) {
            type.button.active = type != sort;
        }
        refresh();
    }

    private void refresh() {
        lastFilterText = search.getValue();
        String filter = lastFilterText.toLowerCase(Locale.ROOT);
        List<LegacyMod> shown = new ArrayList<>(mods.stream().filter(mod -> mod.name().toLowerCase(Locale.ROOT).contains(filter)).toList());
        if (sortType != SortType.NORMAL) {
            Comparator<LegacyMod> byName = Comparator.comparing(mod -> StringUtil.stripColor(mod.name()).toLowerCase(Locale.ROOT));
            shown.sort(sortType == SortType.A_TO_Z ? byName : byName.reversed());
        }
        modList.show(shown);
        if (selected != null && !shown.contains(selected.mod)) {
            select(null);
        }
    }

    private void select(ModList.Entry entry) {
        selected = entry;
        modList.setSelected(entry);
        configButton.active = entry != null && entry.mod.configurable();
        showInfo();
    }

    private void showInfo() {
        releaseLogo();
        if (selected == null) {
            modInfo.setInfo(List.of(), null, 0, 0);
            return;
        }
        LegacyMod mod = selected.mod;
        List<String> lines = new ArrayList<>();
        lines.add(mod.name());
        lines.add(ForgeI18n.parseMessage("fml.menu.mods.info.version", mod.version()));
        lines.add(ForgeI18n.parseMessage("retrofit.mods.info.id", mod.id()));
        if (mod.leftOut() != null) {
            lines.add(ChatFormatting.RED + ForgeI18n.parseMessage("retrofit.mods.info.leftout", mod.leftOut()));
        }
        if (!mod.credits().isEmpty()) {
            lines.add(ForgeI18n.parseMessage("fml.menu.mods.info.credits", mod.credits()));
        }
        if (!mod.authors().isEmpty()) {
            lines.add(ForgeI18n.parseMessage("fml.menu.mods.info.authors", mod.authors()));
        }
        if (!mod.url().isEmpty()) {
            lines.add(ForgeI18n.parseMessage("fml.menu.mods.info.displayurl", mod.url()));
        }
        if (configured) {
            lines.add(null);
            lines.add(ChatFormatting.YELLOW + ForgeI18n.parseMessage("retrofit.mods.info.restart"));
        }
        lines.add(null);
        lines.add(mod.description());
        int[] size = loadLogo(mod);
        modInfo.setInfo(lines, logo, size[0], size[1]);
    }

    /** The mod's logo from its jar (mcmod.info's logoFile); {width, height}, zeros if it has none. */
    private int[] loadLogo(LegacyMod mod) {
        if (mod.logo().isEmpty() || mod.jar() == null || !mod.jar().endsWith(".jar") && !mod.jar().endsWith(".zip")) {
            return new int[2];
        }
        try (ZipFile jar = new ZipFile(mod.jar())) {
            var entry = jar.getEntry(mod.logo().startsWith("/") ? mod.logo().substring(1) : mod.logo());
            if (entry == null) {
                return new int[2];
            }
            try (InputStream in = jar.getInputStream(entry)) {
                NativeImage image = NativeImage.read(in);
                logoTexture = new DynamicTexture(image);
                logo = minecraft.getTextureManager().register("retrofit_legacy_logo", logoTexture);
                return new int[]{image.getWidth(), image.getHeight()};
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read the logo of {}", mod.id(), e);
            return new int[2];
        }
    }

    private void releaseLogo() {
        if (logo != null) {
            minecraft.getTextureManager().release(logo);
            logo = null;
            logoTexture = null;
        }
    }

    /** The mod's configuration screen, the legacy GUI on a screen of its own; back here when it closes. */
    private void openConfig() {
        if (selected == null) {
            return;
        }
        try {
            Object legacyScreen = LegacyBridge.call("modConfigScreen", selected.mod.id(), (Runnable) () -> minecraft.execute(() -> minecraft.setScreen(this)));
            if (legacyScreen != null) {
                configured = true;
                minecraft.setScreen(new LegacyPlainScreen(legacyScreen));
            }
        } catch (RuntimeException e) {
            LegacyBridge.reportClientFailure("the configuration screen of " + selected.mod.id(), e);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        Component text = Component.translatable("fml.menu.mods.search");
        graphics.drawString(font, text.getVisualOrderText(), PADDING + listWidth / 2 - font.width(text) / 2, search.getY() - font.lineHeight, 0xFFFFFF, false);
    }

    @Override
    public void onClose() {
        releaseLogo();
        minecraft.setScreen(parent);
    }

    final class ModList extends ObjectSelectionList<ModList.Entry> {
        ModList(int listWidth, int top, int bottom) {
            super(LegacyModsScreen.this.minecraft, listWidth, LegacyModsScreen.this.height, top, bottom, LegacyModsScreen.this.font.lineHeight * 2 + 8);
        }

        void show(List<LegacyMod> shown) {
            clearEntries();
            shown.forEach(mod -> addEntry(new Entry(mod)));
        }

        @Override
        protected int getScrollbarPosition() {
            return getLeft() + listWidth;
        }

        @Override
        public int getRowWidth() {
            return listWidth;
        }

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            final LegacyMod mod;

            Entry(LegacyMod mod) {
                this.mod = mod;
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", mod.name());
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int entryWidth, int entryHeight, int mouseX, int mouseY,
                               boolean hovered, float partialTick) {
                Font font = LegacyModsScreen.this.font;
                // mods that didn't load in red, as FML's error screens showed them
                graphics.drawString(font, font.plainSubstrByWidth(StringUtil.stripColor(mod.name()), listWidth - 6), left + 3, top + 2,
                        mod.leftOut() == null ? 0xFFFFFF : 0xFF5555, false);
                graphics.drawString(font, font.plainSubstrByWidth(StringUtil.stripColor(mod.version()), listWidth - 6), left + 3,
                        top + 2 + font.lineHeight, 0xCCCCCC, false);
            }

            @Override
            public boolean mouseClicked(double x, double y, int button) {
                select(this);
                return false;
            }
        }
    }

    /** The selected mod's description, as Forge's mod list shows one (links open in the browser). */
    final class InfoPanel extends ScrollPanel {
        private ResourceLocation logoPath;
        private int logoWidth;
        private int logoHeight;
        private List<FormattedCharSequence> lines = List.of();

        InfoPanel(int width, int height, int top) {
            super(LegacyModsScreen.this.minecraft, width, height, top, listWidth + PADDING * 2);
        }

        void setInfo(List<String> text, ResourceLocation logo, int logoWidth, int logoHeight) {
            this.logoPath = logo;
            this.logoWidth = logoWidth;
            this.logoHeight = logoHeight;
            List<FormattedCharSequence> wrapped = new ArrayList<>();
            for (String line : text) {
                if (line == null) {
                    wrapped.add(null);
                } else if (width - 12 >= 0) {
                    wrapped.addAll(net.minecraft.locale.Language.getInstance().getVisualOrder(
                            font.getSplitter().splitLines(ForgeHooks.newChatWithLinks(line, false), width - 12, Style.EMPTY)));
                }
            }
            this.lines = wrapped;
        }

        @Override
        public int getContentHeight() {
            return Math.max(50 + lines.size() * font.lineHeight, bottom - top - 8);
        }

        @Override
        protected int getScrollAmount() {
            return font.lineHeight * 3;
        }

        @Override
        protected void drawPanel(GuiGraphics graphics, int entryRight, int relativeY, Tesselator tess, int mouseX, int mouseY) {
            if (logoPath != null) {
                RenderSystem.enableBlend();
                graphics.blitInscribed(logoPath, left + PADDING, relativeY, width - PADDING * 2, 50, logoWidth, logoHeight, false, true);
                relativeY += 50 + PADDING;
            }
            for (FormattedCharSequence line : lines) {
                if (line != null) {
                    graphics.drawString(font, line, left + PADDING, relativeY, 0xFFFFFF);
                }
                relativeY += font.lineHeight;
            }
            Style style = styleAt(mouseX, mouseY);
            if (style != null) {
                graphics.renderComponentHoverEffect(font, style, mouseX, mouseY);
            }
        }

        private Style styleAt(int mouseX, int mouseY) {
            if (!isMouseOver(mouseX, mouseY)) {
                return null;
            }
            double offset = mouseY - top - PADDING - border + scrollDistance - (logoPath != null ? 50 + PADDING : 0);
            int index = (int) (offset / font.lineHeight);
            if (offset <= 0 || index >= lines.size() || lines.get(index) == null) {
                return null;
            }
            return font.getSplitter().componentStyleAtWidth(lines.get(index), mouseX - left - border);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            Style style = styleAt((int) mouseX, (int) mouseY);
            if (style != null) {
                handleComponentClicked(style);
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public NarrationPriority narrationPriority() {
            return NarrationPriority.NONE;
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
            if (selected != null) {
                output.add(NarratedElementType.TITLE, Component.literal(selected.mod.name()));
            }
        }
    }
}
