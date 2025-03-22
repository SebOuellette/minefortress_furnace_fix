package org.minefortress.registries.events

import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.BlockState
import net.minecraft.item.ItemUsageContext
import net.minecraft.util.ActionResult
import net.minecraft.util.hit.BlockHitResult
import net.remmintan.mods.minefortress.blocks.FortressBlocks
import net.remmintan.mods.minefortress.blueprints.getPersonalBlueprintCell
import net.remmintan.mods.minefortress.blueprints.isBlueprintWorld
import net.remmintan.mods.minefortress.building.BuildingHelper
import net.remmintan.mods.minefortress.core.FortressState
import net.remmintan.mods.minefortress.core.interfaces.client.IClientFortressManager
import net.remmintan.mods.minefortress.core.isClientInFortressGamemode
import net.remmintan.mods.minefortress.core.utils.ClientModUtils
import org.minefortress.utils.BlockUtils

object PlayerBlockEvents {
    fun register() {
        UseBlockCallback.EVENT.register { player, world, _, hitResult ->
            if (!world.isBlueprintWorld() || world.getBlockState(hitResult.blockPos)
                    .isOf(FortressBlocks.FORTRESS_BUILDING_CONFIGURATION)
            )
                return@register ActionResult.PASS

            val cell = player.getPersonalBlueprintCell()
            if (!cell.contains(hitResult.blockPos))
                return@register ActionResult.FAIL

            return@register ActionResult.PASS
        }

        AttackBlockCallback.EVENT.register { player, world, _, blockPos, _ ->
            if (!world.isBlueprintWorld())
                return@register ActionResult.PASS
            val cell = player.getPersonalBlueprintCell()

            return@register if (!cell.contains(blockPos)) ActionResult.FAIL else ActionResult.PASS
        }

        // can't destroy building blocks
        AttackBlockCallback.EVENT.register { _, world, _, blockPos, _ ->
            return@register if (world.getBlockState(blockPos).isOf(FortressBlocks.FORTRESS_BUILDING))
                ActionResult.FAIL else ActionResult.PASS
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (!world.isClient || !isClientInFortressGamemode())
                return@register ActionResult.PASS

            val clientBlueprintManager =
                ClientModUtils.getBlueprintManager()
            val fortressManager =
                ClientModUtils.getFortressManager()

            if (fortressManager.state == FortressState.COMBAT) {
                updateFightSelection(hitResult, fortressManager)
                return@register ActionResult.SUCCESS
            }

            if (fortressManager.state == FortressState.AREAS_SELECTION) {
                val areasClientManager =
                    ClientModUtils.getAreasClientManager()
                if (areasClientManager.isSelecting) areasClientManager.resetSelection()
                else areasClientManager.removeHovered()
                return@register ActionResult.SUCCESS
            }

            if (clientBlueprintManager.isSelecting) {
                clientBlueprintManager.buildCurrentStructure()
                return@register ActionResult.SUCCESS
            }

            val buildingManager =
                ClientModUtils.getBuildingsManager()

            if (buildingManager.isBuildingHovered()) {
                buildingManager.openBuildingScreen(player)
                return@register ActionResult.SUCCESS
            }

            val stackInHand = player.getStackInHand(hand)
            val item = stackInHand.item
            val useoncontext = ItemUsageContext(player, hand, hitResult)
            val blockStateFromItem = BlockUtils.getBlockStateFromItem(item)
            if (blockStateFromItem != null) {
                clickBuild(useoncontext, blockStateFromItem)
                return@register ActionResult.SUCCESS
            }
            val selectionManager =
                ClientModUtils.getSelectionManager()
            if (selectionManager.isSelecting) {
                selectionManager.selectBlock(hitResult.blockPos, null)
                return@register ActionResult.SUCCESS
            }

            return@register ActionResult.PASS
        }

    }

    private fun updateFightSelection(hitResult: BlockHitResult, fortressManager: IClientFortressManager) {
        val fightManager = fortressManager.fightManager
        fightManager.setTarget(hitResult, ClientModUtils.getManagersProvider().targetedSelectionManager)
    }

    private fun clickBuild(useOnContext: ItemUsageContext, blockState: BlockState) {
        var blockPos = useOnContext.blockPos
        if (!BuildingHelper.canPlaceBlock(useOnContext.world, blockPos)) {
            blockPos = blockPos.offset(useOnContext.side)
        }

        ClientModUtils.getSelectionManager()
            .selectBlock(blockPos, blockState)
    }
}

