package org.minefortress.renderer.gui.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.item.Items;
import net.remmintan.mods.minefortress.core.interfaces.tasks.IClientTasksHolder;
import net.remmintan.mods.minefortress.core.utils.ClientModUtils;
import net.remmintan.mods.minefortress.gui.hud.HudState;
import net.remmintan.mods.minefortress.gui.widget.hud.ItemButtonWidget;
import net.remmintan.mods.minefortress.gui.widget.hud.ItemToggleOtherItemWidget;
import net.remmintan.mods.minefortress.gui.widget.hud.TextButtonWidget;
import net.remmintan.mods.minefortress.networking.c2s.ServerboundSleepPacket;
import net.remmintan.mods.minefortress.networking.helpers.FortressChannelNames;
import net.remmintan.mods.minefortress.networking.helpers.FortressClientNetworkHelper;
import org.minefortress.renderer.gui.FortressBookContents;

import java.util.EnumSet;

public class UtilsHudLayer extends AbstractHudLayer {


    protected UtilsHudLayer(MinecraftClient client) {
        super(client);
        this.setBasepoint(5, 5, PositionX.LEFT, PositionY.TOP);

        this.addElement(
            new ItemButtonWidget(
                    0,
                    50,
                    Items.CAMPFIRE,
                    btn -> ClientModUtils.getFortressManager().jumpToCampfire(),
                    "Jump to Campfire"
            ),
            new ItemButtonWidget(
                    0,
                    75,
                    Items.RED_BED,
                    btn -> {
                        final var player = MinecraftClient.getInstance().player;
                        if (player != null && !player.isSleeping()) {
                            FortressClientNetworkHelper.send(FortressChannelNames.FORTRESS_SLEEP, new ServerboundSleepPacket());
                        }
                    },
                    "Skip Night"
            ),
            new ItemToggleOtherItemWidget(
                    0,
                    100,
                    Items.ENDER_EYE,
                    (btn) -> ClientModUtils.getClientTasksHolder().ifPresent(IClientTasksHolder::toggleSelectionVisibility),
                    (button) -> ClientModUtils.getClientTasksHolder().map(IClientTasksHolder::isSelectionHidden)
                            .map(it -> it ? "Show Tasks outline" : "Hide Tasks outline"),
                    () -> ClientModUtils.getClientTasksHolder().map(IClientTasksHolder::isSelectionHidden).orElse(false),
                    () -> true,
                    Items.ENDER_PEARL
            ),
            new TextButtonWidget(
                    0,
                    125,
                    20,
                    20,
                    "?",
                    btn -> {
                        final BookScreen questionsScreen = new BookScreen(new FortressBookContents(FortressBookContents.HELP_BOOK));
                        this.client.setScreen(questionsScreen);
                    },
                    "Help"
            )
        );
    }

    @Override
    public boolean shouldRender(HudState hudState) {
        return EnumSet.of(HudState.AREAS_SELECTION, HudState.BUILD, HudState.COMBAT).contains(hudState);
    }
}
