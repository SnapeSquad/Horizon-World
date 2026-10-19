package ru.hrp.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.hrp.client.HrpProfileState;
import ru.hrp.core.network.HrpNetwork;

/** Form for editing only player-facing roleplay fields. Server validation is authoritative. */
public final class HrpEditProfileScreen extends Screen {
    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 290;

    private EditBox rpNameField;
    private EditBox birthDateField;
    private EditBox biographyField;
    private Button saveButton;

    public HrpEditProfileScreen() {
        super(Component.literal("Редактирование персонажа"));
        HrpProfileState.clearUpdateResult();
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        addRenderableOnly(new PanelWidget(left, top, PANEL_WIDTH, PANEL_HEIGHT));

        var profile = HrpProfileState.get();

        rpNameField = new EditBox(this.font, left + 18, top + 66, 404, 20,
                Component.literal("Имя персонажа"));
        rpNameField.setMaxLength(48); // Server enforces 24 Unicode code points.
        rpNameField.setValue(profile == null ? "" : profile.rpName());
        addRenderableWidget(rpNameField);

        birthDateField = new EditBox(this.font, left + 18, top + 113, 404, 20,
                Component.literal("Дата рождения"));
        birthDateField.setMaxLength(16); // Server validates the exact ISO date format.
        birthDateField.setValue(profile == null ? "" : profile.birthDate());
        addRenderableWidget(birthDateField);

        biographyField = new EditBox(this.font, left + 18, top + 160, 404, 20,
                Component.literal("Биография"));
        biographyField.setMaxLength(200); // Server enforces 160 Unicode code points.
        biographyField.setValue(profile == null ? "" : profile.biography());
        addRenderableWidget(biographyField);

        saveButton = Button.builder(Component.literal("Сохранить"), button -> submit())
                .bounds(left + 18, top + PANEL_HEIGHT - 34, 120, 20)
                .build();
        addRenderableWidget(saveButton);

        addRenderableWidget(Button.builder(Component.literal("Назад"), button -> {
                    if (this.minecraft != null) this.minecraft.setScreen(new HrpMainScreen());
                })
                .bounds(left + PANEL_WIDTH - 138, top + PANEL_HEIGHT - 34, 120, 20)
                .build());

        setInitialFocus(rpNameField);
    }

    private void submit() {
        if (!ClientPlayNetworking.canSend(HrpNetwork.SaveCharacterProfile.TYPE)) {
            HrpProfileState.setUpdateResult(false, "Нет подключения к серверу.");
            return;
        }

        HrpProfileState.setUpdateResult(false, "Отправка данных серверу...");
        saveButton.active = false;
        ClientPlayNetworking.send(new HrpNetwork.SaveCharacterProfile(
                rpNameField.getValue(),
                birthDateField.getValue(),
                biographyField.getValue()
        ));
    }

    @Override
    public void tick() {
        super.tick();
        if (saveButton != null && !saveButton.active
                && !HrpProfileState.getUpdateMessage().equals("Отправка данных серверу...")) {
            saveButton.active = true;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        graphics.fill(left + 16, top + 36, left + PANEL_WIDTH - 16, top + 37, 0x665E6CE8);
        graphics.drawString(this.font, "HRPCORE", left + 18, top + 13, 0xFFF5F5FF, false);
        graphics.drawString(this.font, "ПАРАМЕТРЫ ПЕРСОНАЖА", left + 112, top + 14, 0xFF8F96C5, false);

        graphics.drawString(this.font, "Имя персонажа (2–24 символа)", left + 18, top + 52, 0xFFE5E7F2, false);
        graphics.drawString(this.font, "Дата рождения персонажа — ГГГГ-ММ-ДД, необязательно", left + 18, top + 99, 0xFFE5E7F2, false);
        graphics.drawString(this.font, "Краткая биография (до 160 символов)", left + 18, top + 146, 0xFFE5E7F2, false);
        graphics.drawString(this.font, "Изменения проверяются сервером и сохраняются в базе мира.",
                left + 18, top + 190, 0xFF9DA2B5, false);

        String message = HrpProfileState.getUpdateMessage();
        if (!message.isBlank()) {
            int color = HrpProfileState.isUpdateSuccess() ? 0xFF77DDAA : 0xFFFFC36B;
            graphics.drawString(this.font, truncate(message, 56), left + 18, top + 216, color, false);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 1) + "…";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
