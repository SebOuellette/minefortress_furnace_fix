package org.minefortress.fortress;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.remmintan.mods.minefortress.core.FortressGamemode;
import net.remmintan.mods.minefortress.core.ScreenType;
import net.remmintan.mods.minefortress.core.interfaces.IFortressManager;
import net.remmintan.mods.minefortress.core.interfaces.automation.IAutomationAreaProvider;
import net.remmintan.mods.minefortress.core.interfaces.automation.area.IAutomationArea;
import net.remmintan.mods.minefortress.core.interfaces.automation.server.IServerAutomationAreaManager;
import net.remmintan.mods.minefortress.core.interfaces.blueprints.IServerBlueprintManager;
import net.remmintan.mods.minefortress.core.interfaces.blueprints.ProfessionType;
import net.remmintan.mods.minefortress.core.interfaces.buildings.IServerBuildingsManager;
import net.remmintan.mods.minefortress.core.interfaces.combat.IServerFightManager;
import net.remmintan.mods.minefortress.core.interfaces.entities.IPawnNameGenerator;
import net.remmintan.mods.minefortress.core.interfaces.entities.pawns.IFortressAwareEntity;
import net.remmintan.mods.minefortress.core.interfaces.entities.pawns.IProfessional;
import net.remmintan.mods.minefortress.core.interfaces.entities.pawns.ITargetedPawn;
import net.remmintan.mods.minefortress.core.interfaces.entities.pawns.IWorkerPawn;
import net.remmintan.mods.minefortress.core.interfaces.professions.IServerProfessionsManager;
import net.remmintan.mods.minefortress.core.interfaces.resources.IServerResourceManager;
import net.remmintan.mods.minefortress.core.interfaces.server.*;
import net.remmintan.mods.minefortress.core.interfaces.tasks.IServerTaskManager;
import net.remmintan.mods.minefortress.core.interfaces.tasks.ITasksCreator;
import net.remmintan.mods.minefortress.networking.helpers.FortressChannelNames;
import net.remmintan.mods.minefortress.networking.helpers.FortressServerNetworkHelper;
import net.remmintan.mods.minefortress.networking.s2c.ClientboundSyncFortressManagerPacket;
import net.remmintan.mods.minefortress.networking.s2c.ClientboundTaskExecutedPacket;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.minefortress.blueprints.manager.ServerBlueprintManager;
import org.minefortress.entity.BasePawnEntity;
import org.minefortress.entity.Colonist;
import org.minefortress.entity.colonist.ColonistNameGenerator;
import org.minefortress.fight.ServerFightManager;
import org.minefortress.fortress.automation.areas.AreasServerManager;
import org.minefortress.fortress.automation.areas.ServerAutomationAreaInfo;
import org.minefortress.fortress.buildings.FortressBuildingManager;
import org.minefortress.fortress.resources.gui.craft.FortressCraftingScreenHandlerFactory;
import org.minefortress.fortress.resources.gui.smelt.FurnaceScreenHandlerFactory;
import org.minefortress.fortress.resources.server.ServerResourceManager;
import org.minefortress.professions.ServerProfessionManager;
import org.minefortress.registries.FortressEntities;
import org.minefortress.tasks.RepairBuildingTask;
import org.minefortress.tasks.ServerTaskManager;
import org.minefortress.tasks.TasksCreator;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.remmintan.mods.minefortress.core.interfaces.automation.ProfessionsSelectionType.QUARRY;

public final class ServerFortressManager implements IFortressManager, IServerManagersProvider, IServerFortressManager {

    private static final int DEFAULT_COLONIST_COUNT = 5;

    private final MinecraftServer server;
    private final Set<LivingEntity> pawns = new HashSet<>();
    private final Map<Class<? extends IServerManager>, IServerManager> managers = new HashMap<>();
    
    private IPawnNameGenerator nameGenerator = new ColonistNameGenerator();

    private int maxX = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;
    private int minX = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;

    private FortressGamemode gamemode = FortressGamemode.NONE;

    private boolean needSync = true;

    private BlockPos fortressCenter = null;
    private int maxColonistsCount = -1;

    private boolean spawnPawns = true;

    public ServerFortressManager(MinecraftServer server) {
        this.server = server;

        registerManager(IServerTaskManager.class, new ServerTaskManager());
        registerManager(IServerProfessionsManager.class, new ServerProfessionManager(() -> this, () -> this, server));
        registerManager(IServerResourceManager.class, new ServerResourceManager(server, () -> this));
        registerManager(IServerBuildingsManager.class, new FortressBuildingManager(() -> server.getWorld(World.OVERWORLD), this));
        registerManager(IServerAutomationAreaManager.class, new AreasServerManager());
        registerManager(IServerFightManager.class, new ServerFightManager(this));
        registerManager(ITasksCreator.class, new TasksCreator());
        registerManager(IServerBlueprintManager.class, new ServerBlueprintManager(server));

        if(FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            this.gamemode = FortressGamemode.SURVIVAL;
        }
    }

    private void registerManager(Class<? extends IServerManager> managerInterface, IServerManager manager) {
        managers.put(managerInterface, manager);
    }

    public void addColonist(LivingEntity colonist) {
        pawns.add(colonist);
        scheduleSync();
    }

    @Override
    public void setSpawnPawns(boolean spawnPawns) {
        this.spawnPawns = spawnPawns;
    }

    @Override
    public void spawnDebugEntitiesAroundCampfire(EntityType<? extends IFortressAwareEntity> entityType, int num, ServerPlayerEntity requester) {
        final var infoTag = getColonistInfoTag(requester.getUuid());


        for (int i = 0; i < num; i++) {
            final var spawnPosition = getRandomSpawnPosition();
            final var pawn = entityType.spawn(
                    getWorld(),
                    infoTag,
                    (it) -> {},
                    spawnPosition,
                    SpawnReason.EVENT,
                    true,
                    false
            );
            if(pawn instanceof LivingEntity le)
                pawns.add(le);
        }
        getFightManager().sync();
    }

    public void tick(@Nullable final ServerPlayerEntity player) {
        tickFortress(player);

        for (IServerManager it : managers.values()) {
            if(it instanceof ITickableManager tickableManager)
                tickableManager.tick(player);
        }

        if(!needSync || player == null) return;
        final var isServer = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
        final var syncFortressPacket = new ClientboundSyncFortressManagerPacket(pawns.size(),
                fortressCenter,
                gamemode,
                isServer,
                maxColonistsCount,
                getReservedPawnsCount());
        FortressServerNetworkHelper.send(player, FortressChannelNames.FORTRESS_MANAGER_SYNC, syncFortressPacket);
        needSync = false;
    }

    public void replaceColonistWithTypedPawn(LivingEntity colonist, String warriorId, EntityType<? extends LivingEntity> entityType) {
        final var pos = getRandomSpawnPosition();
        final var world = (ServerWorld) colonist.getEntityWorld();
        final var masterId = ((Colonist)colonist).getMasterId().orElseThrow(() -> new IllegalStateException("Colonist has no master!"));

        final var infoTag = getColonistInfoTag(masterId);
        infoTag.putString(ServerProfessionManager.PROFESSION_NBT_TAG, warriorId);

        colonist.damage(getOutOfWorldDamageSource(), Float.MAX_VALUE);
        pawns.remove(colonist);
        final var typedReplacement = entityType.spawn(world, infoTag, (it) -> {}, pos, SpawnReason.EVENT, true, false);
        pawns.add(typedReplacement);
        getFightManager().sync();
    }

    public void tickFortress(@Nullable ServerPlayerEntity player) {
        keepColonistsBelowMax();

        final var deadPawns = pawns.stream()
                .filter(is -> !is.isAlive()).toList();
        if(!deadPawns.isEmpty()) {
            for(LivingEntity pawn : deadPawns) {
                if(pawn instanceof IProfessional professional) {
                    final String professionId = professional.getProfessionId();
                    getProfessionsManager().decreaseAmount(professionId, true);
                }
                pawns.remove(pawn);
            }
            scheduleSync();
        }


        if(this.fortressCenter != null) {
            final var colonistsCount = this.pawns.size();
            final var spawnFactor = MathHelper.clampedLerp(82, 99, colonistsCount / 50f);
            if(spawnPawns && (maxColonistsCount == -1 || colonistsCount < maxColonistsCount)) {
                if(getWorld().getTime() % 100 == 0  && getWorld().random.nextInt(100) >= spawnFactor) {
                    final long bedsCount = getBuildingsManager().getTotalBedsCount();
                    if(colonistsCount < bedsCount || colonistsCount < DEFAULT_COLONIST_COUNT) {
                        if(player != null) {
                            spawnPawnNearCampfire(player.getUuid())
                                    .ifPresent(it -> player.sendMessage(Text.literal(it.getName().getString() + " appeared in the village."), false));
                        }
                    }
                }
            }
        }
    }

    private void keepColonistsBelowMax() {
        if(maxColonistsCount != -1 && getTotalColonistsCount() > maxColonistsCount) {
            final var deltaColonists = Math.max( pawns.stream().filter(LivingEntity::isAlive).count() - maxColonistsCount, 0);

            pawns.stream()
                    .filter(LivingEntity::isAlive)
                    .limit(deltaColonists)
                    .forEach(it -> it.damage(getOutOfWorldDamageSource(), Integer.MAX_VALUE));
        }
    }

    public int getReservedPawnsCount() {
        return (int) getProfessionals()
                .stream()
                .filter(it -> it.getProfessionId().equals(Colonist.RESERVE_PROFESSION_ID))
                .count();
    }

    public void killAllPawns() {
        final var outOfWorldDamageSource = getOutOfWorldDamageSource();
        pawns.forEach(it -> it.damage(outOfWorldDamageSource, 40f));
    }

    private DamageSource getOutOfWorldDamageSource() {
        final var world = server.getWorld(World.OVERWORLD);
        if(world == null)
            throw new IllegalStateException("World is null");
        return world.getDamageSources().outOfWorld();
    }

    private Stream<IWorkerPawn> getWorkersStream() {
        return pawns
                .stream()
                .filter(IWorkerPawn.class::isInstance)
                .map(IWorkerPawn.class::cast);
    }

    private boolean allPawnsAreFree() {
        return getWorkersStream().noneMatch(it -> it.getTaskControl().hasTask());
    }

    public Optional<LivingEntity> spawnPawnNearCampfire(UUID masterPlayerId) {
        final var randomSpawnPosition = getRandomSpawnPosition();

        final var tag = getColonistInfoTag(masterPlayerId);
        final var colonistType = FortressEntities.COLONIST_ENTITY_TYPE;
        final var world = getWorld();
        final var spawnedPawn = colonistType.spawn(world, tag, (it) -> {}, randomSpawnPosition, SpawnReason.MOB_SUMMONED, true, false);
        return Optional.ofNullable(spawnedPawn);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getManager(Class<T> managerClass) {
        final var serverManager = managers.get(managerClass);
        if(managerClass.isAssignableFrom(serverManager.getClass()))
            return (T) serverManager;
        else
            throw new IllegalStateException("Manager " + managerClass.getSimpleName() + " is not assignable from " + serverManager.getClass().getSimpleName());
    }

    @Override
    public void setupCenter(@NotNull BlockPos fortressCenter, ServerPlayerEntity player) {
        this.fortressCenter = fortressCenter;

        if(minX > this.fortressCenter.getX()-10) minX = this.fortressCenter.getX()-10;
        if(minZ > this.fortressCenter.getZ()-10) minZ = this.fortressCenter.getZ()-10;
        if(maxX < this.fortressCenter.getX()+10) maxX = this.fortressCenter.getX()+10;
        if(maxZ < this.fortressCenter.getZ()+10) maxZ = this.fortressCenter.getZ()+10;

        for (int i = 0; i < 5; i++) {
            spawnPawnNearCampfire(player.getUuid());
        }

        player.setSpawnPoint(getWorld().getRegistryKey(), player.getBlockPos(), 0, true, false);

        this.scheduleSync();
    }

    @Override
    public void openHandledScreen(ScreenType type, ServerPlayerEntity player, BlockPos pos) {
        switch (type) {
            case CRAFTING -> player.openHandledScreen(new FortressCraftingScreenHandlerFactory());
            case FURNACE -> player.openHandledScreen(new FurnaceScreenHandlerFactory(pos));
        }
    }

    @Override
    public void jumpToCampfire(ServerPlayerEntity player) {
        if(fortressCenter == null) return;
        if(player.getWorld().getRegistryKey() != World.OVERWORLD) return;
        player.setPitch(60);
        player.setYaw(90 + 45);
        player.teleport(fortressCenter.getX() + 10, fortressCenter.getY() + 20, fortressCenter.getZ() + 10);
    }

    @Override
    public void teleportToCampfireGround(ServerPlayerEntity player) {
        if (fortressCenter == null) return;
        if (player.getWorld().getRegistryKey() != World.OVERWORLD) return;

        // Get a position on the ground near the campfire
        BlockPos groundPos = getRandomSpawnPosition();

        // Set the player's orientation to look at the campfire
        double dx = fortressCenter.getX() - groundPos.getX();
        double dz = fortressCenter.getZ() - groundPos.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx));

        player.setPitch(0); // Look straight ahead
        player.setYaw(yaw);
        player.teleport(groundPos.getX(), groundPos.getY(), groundPos.getZ());
    }

    private static NbtCompound getColonistInfoTag(UUID masterPlayerId) {
        final NbtCompound nbtCompound = new NbtCompound();
        nbtCompound.putUuid(BasePawnEntity.FORTRESS_ID_NBT_KEY, masterPlayerId);
        return nbtCompound;
    }

    private BlockPos getRandomSpawnPosition() {
        int spawnX, spawnZ, spawnY;
        do {
            spawnX = fortressCenter.getX() + getWorld().random.nextInt(10) - 5;
            spawnZ = fortressCenter.getZ() + getWorld().random.nextInt(10) - 5;
            spawnY = getWorld().getTopY(Heightmap.Type.WORLD_SURFACE, spawnX, spawnZ);
        } while (spawnX == fortressCenter.getX() && spawnZ == fortressCenter.getZ());

        return new BlockPos(spawnX, spawnY, spawnZ);
    }

    @Override
    public void syncOnJoin() {
        this.needSync = true;
        getAutomationAreaManager().sync();
        getFightManager().sync();
    }


    public void scheduleSync() {
        needSync = true;
    }
    public Set<IProfessional> getProfessionals() {
        return pawns
                .stream()
                .filter(IProfessional.class::isInstance)
                .map(IProfessional.class::cast)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void writeToNbt(NbtCompound tag) {
        if(fortressCenter != null) {
            tag.putInt("centerX", fortressCenter.getX());
            tag.putInt("centerY", fortressCenter.getY());
            tag.putInt("centerZ", fortressCenter.getZ());
        }

        tag.putInt("minX", minX);
        tag.putInt("minZ", minZ);
        tag.putInt("maxX", maxX);
        tag.putInt("maxZ", maxZ);

        final NbtCompound nameGeneratorTag = new NbtCompound();
        this.nameGenerator.write(nameGeneratorTag);
        tag.put("nameGenerator", nameGeneratorTag);
        tag.putString("gamemode", this.gamemode.name());

        if(maxColonistsCount != -1) {
            tag.putInt("maxColonistsCount", maxColonistsCount);
        }

        for (IServerManager value : managers.values()) {
            if(value instanceof IWritableManager wm) {
                wm.write(tag);
            }
        }

        tag.putBoolean("spawnPawns", spawnPawns);
    }

    public void readFromNbt(NbtCompound tag) {
        final int centerX = tag.getInt("centerX");
        final int centerY = tag.getInt("centerY");
        final int centerZ = tag.getInt("centerZ");
        if(centerX != 0 || centerY != 0 || centerZ != 0) {
            fortressCenter = new BlockPos(centerX, centerY, centerZ);
        }

        if(tag.contains("minX")) minX = tag.getInt("minX");
        if(tag.contains("minZ")) minZ = tag.getInt("minZ");
        if(tag.contains("maxX")) maxX = tag.getInt("maxX");
        if(tag.contains("maxZ")) maxZ = tag.getInt("maxZ");

        if(tag.contains("nameGenerator")) {
            final NbtCompound nameGeneratorTag = tag.getCompound("nameGenerator");
            this.nameGenerator = new ColonistNameGenerator(nameGeneratorTag);
        }

        if(tag.contains("gamemode")) {
            final String gamemodeName = tag.getString("gamemode");
            final FortressGamemode fortressGamemode = FortressGamemode.valueOf(gamemodeName);
            this.setGamemode(fortressGamemode);
        }


        if(tag.contains("maxColonistsCount")) {
            this.maxColonistsCount = tag.getInt("maxColonistsCount");
        }

        for (IServerManager value : managers.values()) {
            if(value instanceof IWritableManager rm) {
                rm.read(tag);
            }
        }

        if(tag.contains("spawnPawns")) {
            this.spawnPawns = tag.getBoolean("spawnPawns");
        }
        getFightManager().sync();
        getProfessionsManager().scheduleSync();
        getResourceManager().syncAll();
        this.scheduleSync();
    }

    @Override
    public Optional<IAutomationArea> getAutomationAreaByProfessionType(ProfessionType professionType, ServerPlayerEntity masterPlayer) {
        if(getBuildingsManager() instanceof IAutomationAreaProvider provider) {
            final var buildings = provider.getAutomationAreaByProfessionType(professionType);
            final var automationAreaManager = getAutomationAreaManager();
            final var areas = automationAreaManager.getByProfessionType(professionType);

            final var areaOpt = Stream
                    .concat(buildings, areas)
                    .min(Comparator.comparing(IAutomationArea::getUpdated));

            if(areaOpt.isPresent()) {
                final var area = areaOpt.get();
                if(area instanceof ServerAutomationAreaInfo saai && area.isEmpty(getWorld()) && saai.getAreaType() == QUARRY) {
                    if(masterPlayer != null)
                        area.sendFinishMessage(masterPlayer);
                    automationAreaManager.removeArea(area.getId());
                    return getAutomationAreaByProfessionType(professionType, masterPlayer);
                } else {
                    return areaOpt;
                }
            } else {
                return areaOpt;
            }


        }
        return Optional.empty();
    }

    public IPawnNameGenerator getNameGenerator() {
        return nameGenerator;
    }

    public BlockPos getFortressCenter() {
        return fortressCenter!=null?fortressCenter.toImmutable():null;
    }

    public boolean isPositionWithinFortress(BlockPos pos) {
        if(minX == Integer.MAX_VALUE) {
            return false;
        }

        return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    public Optional<BlockPos> getRandomPositionAroundCampfire() {
        final var fortressCenter = getFortressCenter();
        if(fortressCenter == null) return Optional.empty();

        final var random = getWorld().random;

        final var radius = Math.sqrt(random.nextDouble());
        final var angle = random.nextDouble() * 2 * Math.PI;
        final var x = (int) Math.round(radius * Math.cos(angle) * getCampfireWarmRadius());
        final var z = (int) Math.round(radius * Math.sin(angle) * getCampfireWarmRadius());

        final var blockX = fortressCenter.getX() + x;
        final var blockZ = fortressCenter.getZ() + z;
        final var blockY = getWorld().getTopY(Heightmap.Type.WORLD_SURFACE, blockX, blockZ);

        return Optional.of(new BlockPos(blockX, blockY, blockZ));
    }

    public double getCampfireWarmRadius() {
        return Math.max(Math.sqrt(getTotalColonistsCount()), 4);
    }

    @Override
    public boolean hasRequiredBuilding(ProfessionType type, int level, int minCount) {
        return getBuildingsManager().hasRequiredBuilding(type, level, minCount);
    }

    @Override
    public int getTotalColonistsCount() {
        return this.pawns.size();
    }

    public Optional<IProfessional> getPawnWithoutAProfession() {
        return pawns
                .stream()
                .filter(Colonist.class::isInstance)
                .map(IProfessional.class::cast)
                .filter(it -> it.getProfessionId().equals(Colonist.DEFAULT_PROFESSION_ID))
                .findAny();
    }

    public List<IWorkerPawn> getFreeWorkers() {
        return getWorkersStream()
                .filter(it -> !it.getTaskControl().hasTask() && !it.getTaskControl().isDoingEverydayTasks())
                .toList();
    }

    @Override
    public List<ITargetedPawn> getAllTargetedPawns() {
        return pawns
                .stream()
                .filter(ITargetedPawn.class::isInstance)
                .map(ITargetedPawn.class::cast)
                .toList();
    }

    @Override
    public void setGamemode(FortressGamemode gamemode) {
        this.gamemode = gamemode;
        this.scheduleSync();
    }

    @Override
    public boolean isCreative() {
        return gamemode == FortressGamemode.CREATIVE;
    }

    @Override
    public boolean isSurvival() {
        return gamemode != null && gamemode == FortressGamemode.SURVIVAL;
    }

    private ServerWorld getWorld() {
        return this.server.getWorld(World.OVERWORLD);
    }

    @Override
    public void increaseMaxColonistsCount() {
        if(maxColonistsCount == -1) return;
        this.maxColonistsCount++;
        if(this.maxColonistsCount >= getTotalColonistsCount()) {
            this.maxColonistsCount = -1;
        }
        this.scheduleSync();
    }

    @Override
    public void decreaseMaxColonistsCount() {
        if(maxColonistsCount == -1)
            this.maxColonistsCount = getTotalColonistsCount();

        this.maxColonistsCount--;

        if(this.maxColonistsCount <= 0)
            this.maxColonistsCount = 1;
        this.scheduleSync();
    }

    public void expandTheVillage(BlockPos pos) {
        if(maxX < pos.getX()) maxX = pos.getX();
        if(minX > pos.getX()) minX = pos.getX();
        if(maxZ < pos.getZ()) maxZ = pos.getZ();
        if(minZ > pos.getZ()) minZ = pos.getZ();
    }

    public double getVillageRadius() {
        final var radius1 = flatDistanceToCampfire(maxX, maxZ);
        final var radius2 = flatDistanceToCampfire(minX, minZ);
        final var radius3 = flatDistanceToCampfire(maxX, minZ);
        final var radius4 = flatDistanceToCampfire(minX, maxZ);

        return Math.max(Math.max(radius1, radius2), Math.max(radius3, radius4));
    }

    @Override
    public void repairBuilding(ServerPlayerEntity player, BlockPos pos, List<Integer> selectedPawns) {
        final var buildingManager = getBuildingsManager();
        final var resourceManager = getResourceManager();

        final var taskId = UUID.randomUUID();
        try {
            final var building = buildingManager.getBuilding(pos)
                    .orElseThrow(() -> new IllegalStateException("Building not found"));

            final var itemInfos = building.getRepairItemInfos();
            final var blocksToRepair = building.getBlocksToRepair();

            if(this.isSurvival()) {
                resourceManager.reserveItems(taskId, itemInfos);
            }

            final var task = new RepairBuildingTask(taskId, building.getStart(), building.getEnd(), blocksToRepair);
            getTaskManager().addTask(task, selectedPawns, player);
        } catch (RuntimeException exp) {
            LogManager.getLogger().error("Error while repairing building", exp);
            FortressServerNetworkHelper.send(player, FortressChannelNames.FINISH_TASK, new ClientboundTaskExecutedPacket(taskId));
        }
    }

    private double flatDistanceToCampfire(double x, double z) {
        final var campfireX = fortressCenter.getX();
        final var campfireZ = fortressCenter.getZ();

        final var deltaX = x - campfireX;
        final var deltaZ = z - campfireZ;

        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }

}
