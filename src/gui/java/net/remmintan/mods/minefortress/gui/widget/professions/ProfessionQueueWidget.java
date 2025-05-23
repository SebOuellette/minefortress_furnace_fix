package net.remmintan.mods.minefortress.gui.widget.professions;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Supplier;

public class ProfessionQueueWidget extends BaseProfessionWidget implements Drawable, Element {

    private final int x;
    private final int y;
    private final Supplier<Integer> amountSupplier;

    public ProfessionQueueWidget(int x, int y, Supplier<Integer> amountSupplier) {
        this.x = x;
        this.y = y;
        this.amountSupplier = amountSupplier;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        final var stack = Items.PLAYER_HEAD.getDefaultStack();
        drawContext.drawItem(stack, x, y);
        drawContext.drawText(getTextRenderer(), String.valueOf(amountSupplier.get()), x+14, y+10, 0xFFFFFF, false);

        if (mouseX >= x && mouseX <= x + 16 && mouseY >= y && mouseY <= y + 16) {
            final var tooltip = List.of("Recruitment Queue: Shows the", "number of units awaiting", "recruitment.").stream().map(Text::of).toList();
            drawContext.drawTooltip(getTextRenderer(), tooltip, mouseX, mouseY);
        }
    }
}
