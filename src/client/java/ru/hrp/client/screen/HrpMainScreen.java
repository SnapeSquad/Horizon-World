package ru.hrp.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.hrp.client.HrpProfileState;

/** First UI shell: custom RP navigation while keeping the vanilla HUD intact. */
public final class HrpMainScreen extends Screen {
    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 260;

    private String activeTab = "ПРОФИЛЬ";

    public HrpMainScreen() {
        super(Component.literal("HRPCore"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        // This non-interactive widget is inserted first so it renders behind the buttons.
        addRenderableOnly(new PanelWidget(left, top, PANEL_WIDTH, PANEL_HEIGHT));

        String[] tabs = {"ПРОФИЛЬ", "ФИНАНСЫ", "ПРОФЕССИЯ", "ТАЛАНТЫ", "ФРАКЦИЯ"};
        int tabWidth = 80;
        int gap = 4;
        for (int i = 0; i < tabs.length; i++) {
            final String tab = tabs[i];
            int x = left + 16 + i * (tabWidth + gap);
            addRenderableWidget(Button.builder(Component.literal(tab), button -> activeTab = tab)
                .bounds(x, top + 48, tabWidth, 22)
                .build());
        }

        addRenderableWidget(Button.builder(Component.literal("Закрыть"), button -> onClose())
            .bounds(left + PANEL_WIDTH - 94, top + PANEL_HEIGHT - 34, 78, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        var profile = HrpProfileState.get();

        if ("ПРОФИЛЬ".equals(activeTab)) {
            if (profile == null) {
                graphics.drawString(this.font, "Загрузка профиля...",
                        left + 18, top + 185, 0xFFFFCC66, false);
            } else {
                graphics.drawString(this.font, "Имя: " + profile.name(),
                        left + 18, top + 180, 0xFFFFFFFF, false);
                graphics.drawString(this.font, "UUID: " + profile.uuid(),
                        left + 18, top + 195, 0xFF9DA2B5, false);
                graphics.drawString(this.font,
                        "Первый вход: " + java.time.Instant.ofEpochMilli(profile.firstJoin()),
                        left + 18, top + 210, 0xFF9DA2B5, false);
                graphics.drawString(this.font,
                        "Время игры: " + (profile.playTimeTicks() / 20 / 60) + " мин.",
                        left + 18, top + 225, 0xFF9DA2B5, false);
            }
        }

        graphics.fill(left + 16, top + 36, left + PANEL_WIDTH - 16, top + 37, 0x665E6CE8);
        graphics.drawString(this.font, "HRPCORE", left + 18, top + 13, 0xFFF5F5FF, false);
        graphics.drawString(this.font, "ROLEPLAY SYSTEM", left + 112, top + 14, 0xFF8F96C5, false);
        graphics.drawString(this.font, activeTab, left + 18, top + 88, 0xFF9AA7FF, false);
        graphics.drawString(this.font, getTabDescription(), left + 18, top + 110, 0xFFE5E7F2, false);

        if ("ПРОФИЛЬ".equals(activeTab)) {
            graphics.drawString(this.font, "Профиль передаётся сервером.",
                    left + 18, top + 143, 0xFF9DA2B5, false);
            graphics.drawString(this.font, "Остальные разделы будут подключаться поэтапно.",
                    left + 18, top + 158, 0xFF9DA2B5, false);
        } else {
            graphics.drawString(this.font, "Сервер остаётся источником игровых данных.",
                    left + 18, top + 143, 0xFF9DA2B5, false);
            graphics.drawString(this.font, "Этот раздел пока находится в разработке.",
                    left + 18, top + 158, 0xFF9DA2B5, false);
        }
    }

    private String getTabDescription() {
        return switch (activeTab) {
            case "ФИНАНСЫ" -> "Банк, баланс, переводы и история операций";
            case "ПРОФЕССИЯ" -> "Текущая профессия, прогресс и доступные задания";
            case "ТАЛАНТЫ" -> "Дерево улучшений и способности персонажа";
            case "ФРАКЦИЯ" -> "Фракция, должность и служебные полномочия";
            default -> "Профиль персонажа и сводка состояния";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
