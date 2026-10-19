package ru.hrp.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.hrp.client.HrpFinanceState;
import ru.hrp.core.network.HrpNetwork;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Finance screen. All balance changes and validations are performed by the server. */
public final class HrpFinanceScreen extends Screen {
    private static final int PANEL_WIDTH = 480;
    private static final int PANEL_HEIGHT = 370;

    private EditBox amountField;
    private EditBox targetField;
    private Button depositButton;
    private Button withdrawButton;
    private Button transferButton;
    private long pendingStartedAt;

    public HrpFinanceScreen() {
        super(Component.literal("Финансы HRPCore"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;
        addRenderableOnly(new PanelWidget(left, top, PANEL_WIDTH, PANEL_HEIGHT));

        amountField = new EditBox(this.font, left + 18, top + 108, 190, 20,
                Component.literal("Сумма в рублях"));
        amountField.setMaxLength(18);
        amountField.setValue("100,00");
        addRenderableWidget(amountField);

        targetField = new EditBox(this.font, left + 18, top + 153, 190, 20,
                Component.literal("Ник игрока"));
        targetField.setMaxLength(16);
        addRenderableWidget(targetField);

        depositButton = Button.builder(Component.literal("Внести в банк"), button -> submit(0))
                .bounds(left + 18, top + 185, 126, 20).build();
        withdrawButton = Button.builder(Component.literal("Снять"), button -> submit(1))
                .bounds(left + 150, top + 185, 126, 20).build();
        transferButton = Button.builder(Component.literal("Перевести"), button -> submit(2))
                .bounds(left + 282, top + 185, 126, 20).build();
        addRenderableWidget(depositButton);
        addRenderableWidget(withdrawButton);
        addRenderableWidget(transferButton);

        addRenderableWidget(Button.builder(Component.literal("Назад"), button -> {
                    if (this.minecraft != null) this.minecraft.setScreen(new HrpMainScreen());
                })
                .bounds(left + PANEL_WIDTH - 100, top + PANEL_HEIGHT - 31, 82, 20).build());

        setInitialFocus(amountField);
        requestFinance();
    }

    private void requestFinance() {
        if (!ClientPlayNetworking.canSend(HrpNetwork.RequestFinance.TYPE)) {
            HrpFinanceState.setLocalError("Подключись к миру или серверу, чтобы открыть финансы.");
            return;
        }
        HrpFinanceState.setPending("Загрузка финансовых данных...");
        pendingStartedAt = System.currentTimeMillis();
        ClientPlayNetworking.send(new HrpNetwork.RequestFinance());
    }

    private void submit(int action) {
        if (HrpFinanceState.isPending()) return;
        if (!ClientPlayNetworking.canSend(HrpNetwork.FinanceAction.TYPE)) {
            HrpFinanceState.setLocalError("Нет подключения к серверу.");
            return;
        }

        long amountCents;
        try {
            String normalized = amountField.getValue().trim().replace(" ", "").replace(',', '.');
            BigDecimal amount = new BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("100000000.00")) > 0) {
                throw new ArithmeticException("Amount out of bounds");
            }
            amountCents = amount.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            HrpFinanceState.setLocalError("Укажи сумму, например 125,50. Максимум — 100 000 000,00 ₽.");
            return;
        }

        String target = targetField.getValue().trim();
        if (action == 2 && !target.matches("[A-Za-z0-9_]{3,16}")) {
            HrpFinanceState.setLocalError("Для перевода укажи ник Minecraft длиной от 3 до 16 символов.");
            return;
        }

        HrpFinanceState.setPending("Операция обрабатывается сервером...");
        pendingStartedAt = System.currentTimeMillis();
        ClientPlayNetworking.send(new HrpNetwork.FinanceAction(action, amountCents, target));
    }

    @Override
    public void tick() {
        super.tick();
        boolean enabled = !HrpFinanceState.isPending();
        if (depositButton != null) depositButton.active = enabled;
        if (withdrawButton != null) withdrawButton.active = enabled;
        if (transferButton != null) transferButton.active = enabled;
        if (HrpFinanceState.isPending() && System.currentTimeMillis() - pendingStartedAt > 15_000L) {
            HrpFinanceState.setLocalError("Сервер не ответил. Закрой экран и попробуй снова.");
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = (this.width - PANEL_WIDTH) / 2;
        int top = (this.height - PANEL_HEIGHT) / 2;

        graphics.fill(left + 16, top + 36, left + PANEL_WIDTH - 16, top + 37, 0x665E6CE8);
        graphics.drawString(this.font, "HRPCORE", left + 18, top + 13, 0xFFF5F5FF, false);
        graphics.drawString(this.font, "ФИНАНСЫ И БАНК", left + 112, top + 14, 0xFF8F96C5, false);

        HrpNetwork.FinanceResponse snapshot = HrpFinanceState.getSnapshot();
        if (snapshot == null) {
            graphics.drawString(this.font, "Наличные: загрузка...", left + 18, top + 55, 0xFFE5E7F2, false);
            graphics.drawString(this.font, "Банковский счёт: загрузка...", left + 18, top + 72, 0xFFE5E7F2, false);
        } else {
            graphics.drawString(this.font, "Наличные: " + formatMoney(snapshot.cashCents()),
                    left + 18, top + 55, 0xFFFFFFFF, false);
            graphics.drawString(this.font, "Банковский счёт: " + formatMoney(snapshot.bankCents()),
                    left + 18, top + 72, 0xFF9AA7FF, false);
        }

        graphics.drawString(this.font, "Сумма в рублях", left + 18, top + 95, 0xFFE5E7F2, false);
        graphics.drawString(this.font, "Ник получателя для перевода", left + 18, top + 140, 0xFFE5E7F2, false);

        String status = HrpFinanceState.getMessage();
        if (!status.isBlank()) {
            int statusColor = HrpFinanceState.isPending() ? 0xFFFFCC66
                    : (HrpFinanceState.isSuccess() ? 0xFF77DDAA : 0xFFFFA0A0);
            graphics.drawString(this.font, truncate(status, 64), left + 18, top + 214, statusColor, false);
        }

        graphics.drawString(this.font, "ПОСЛЕДНИЕ ОПЕРАЦИИ", left + 18, top + 238, 0xFF9AA7FF, false);
        if (snapshot == null || snapshot.history().isEmpty()) {
            graphics.drawString(this.font, "История пока пуста.", left + 18, top + 256, 0xFF9DA2B5, false);
        } else {
            int lines = Math.min(6, snapshot.history().size());
            for (int i = 0; i < lines; i++) {
                graphics.drawString(this.font, truncate(snapshot.history().get(i), 66),
                        left + 18, top + 256 + i * 13, 0xFFCDD0DF, false);
            }
        }
    }

    private static String formatMoney(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',') + " ₽";
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return "";
        if (value.length() <= maximum) return value;
        return value.substring(0, maximum - 1) + "…";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
