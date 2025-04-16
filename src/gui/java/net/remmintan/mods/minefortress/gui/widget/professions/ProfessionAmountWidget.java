package net.remmintan.mods.minefortress.gui.widget.professions;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.function.Supplier;

public class ProfessionAmountWidget extends BaseProfessionWidget implements Drawable, Element {

    private final int x;
    private final int y;
    private final ItemStack stack;
    private final Supplier<Integer> amountSupplier;
    private final Supplier<Integer> maxAmountSupplier;

    public ProfessionAmountWidget(int x, int y, ItemStack stack, Supplier<Integer> profAmountSupplier, Supplier<Integer> maxAmountSupplier) {
        this.x = x;
        this.y = y;
        this.stack = stack;
        this.amountSupplier = profAmountSupplier;
        this.maxAmountSupplier = maxAmountSupplier;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        drawContext.drawItem(stack, x, y);
        final var currentAmount = amountSupplier.get();
        final var maxAmount = maxAmountSupplier.get();
        drawContext.drawText(getTextRenderer(), "%d/%d".formatted(currentAmount, maxAmount), x + 15, y + 9, 0xFFFFFF, false);

        if (isHovered(mouseX, mouseY)) {
            drawContext.drawTooltip(getTextRenderer(), Text.of("Amount of units"), mouseX, mouseY);
        }
    }

    private boolean isHovered(int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + 16 && mouseY >= y && mouseY <= y + 16;
    }

    @Override
    public void setFocused(boolean focused) {

    }

    @Override
    public boolean isFocused() {
        return false;
    }
}
