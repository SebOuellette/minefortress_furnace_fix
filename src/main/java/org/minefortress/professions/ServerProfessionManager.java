package org.minefortress.professions;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.annotation.MethodsReturnNonnullByDefault;
import net.remmintan.mods.minefortress.core.dtos.professions.IProfessionEssentialInfo;
import net.remmintan.mods.minefortress.core.dtos.professions.ProfessionFullInfo;
import net.remmintan.mods.minefortress.core.interfaces.IFortressManager;
import net.remmintan.mods.minefortress.core.interfaces.entities.pawns.IProfessional;
import net.remmintan.mods.minefortress.core.interfaces.professions.CountProfessionals;
import net.remmintan.mods.minefortress.core.interfaces.professions.IProfession;
import net.remmintan.mods.minefortress.core.interfaces.professions.IServerProfessionsManager;
import net.remmintan.mods.minefortress.core.interfaces.professions.ProfessionResearchState;
import net.remmintan.mods.minefortress.core.interfaces.server.IServerFortressManager;
import net.remmintan.mods.minefortress.core.interfaces.server.IServerManagersProvider;
import net.remmintan.mods.minefortress.core.interfaces.server.ITickableManager;
import net.remmintan.mods.minefortress.core.interfaces.server.IWritableManager;
import net.remmintan.mods.minefortress.networking.helpers.FortressChannelNames;
import net.remmintan.mods.minefortress.networking.helpers.FortressServerNetworkHelper;
import net.remmintan.mods.minefortress.networking.s2c.ClientboundProfessionSyncPacket;
import net.remmintan.mods.minefortress.networking.s2c.ClientboundProfessionsInitPacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.minefortress.fortress.ServerFortressManager;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@MethodsReturnNonnullByDefault
public final class ServerProfessionManager extends ProfessionManager implements IServerProfessionsManager, ITickableManager, IWritableManager {
    public static final String PROFESSION_NBT_TAG = "professionId";

    private final ProfessionEntityTypesMapper profToEntityMapper = new ProfessionEntityTypesMapper();
    private final MinecraftServer server;
    private List<ProfessionFullInfo> professionsInfos;
    private String professionsTree;
    private boolean needsUpdate = false;


    private final Supplier<IServerManagersProvider> serverManagersProviderSupplier;

    public ServerProfessionManager(Supplier<IFortressManager> fortressManagerSupplier, Supplier<IServerManagersProvider> serverManagersProviderSupplier, MinecraftServer server) {
        super(fortressManagerSupplier);
        this.server = server;
        this.serverManagersProviderSupplier = serverManagersProviderSupplier;
    }

    @Override
    public void increaseAmount(String professionId) {
        final IProfession profession = super.getProfession(professionId);
        if(profession == null) return;
        
        // Check for reserved pawns
        if(this.fortressManagerSupplier.get() instanceof ServerFortressManager fsm && fsm.getReservedPawnsCount() <= 0) {
            LoggerFactory.getLogger(ServerProfessionManager.class).error("No reserved pawns but trying to hire a profession");
            return;
        }

        if (super.isRequirementsFulfilled(profession, CountProfessionals.INCREASE) != ProfessionResearchState.UNLOCKED)
            return;

        profession.setAmount(profession.getAmount() + 1);
        scheduleSync();
    }

    public void decreaseAmount(String professionId, boolean force) {
        // Only allow decreasing if force is true (for internal use)
        if (!force) return;
        
        final IProfession profession = super.getProfession(professionId);
        if(profession == null) return;
        if(profession.getAmount() <= 0) return;

        profession.setAmount(profession.getAmount() - 1);
        scheduleSync();
    }

    @Override
    public void tick(@Nullable ServerPlayerEntity player) {
        if(player == null) return;

        if(needsUpdate) {
            final var essentialInfos = new ArrayList<IProfessionEssentialInfo>();
            for(Map.Entry<String, IProfession> entry : getProfessions().entrySet())  {
                final ProfessionEssentialInfo professionEssentialInfo = new ProfessionEssentialInfo(entry.getKey(), entry.getValue().getAmount());
                essentialInfos.add(professionEssentialInfo);
            }

            ClientboundProfessionSyncPacket packet = new ClientboundProfessionSyncPacket(essentialInfos);
            FortressServerNetworkHelper.send(player, FortressChannelNames.FORTRESS_PROFESSION_SYNC, packet);
            needsUpdate = false;
        }
    }

    @Override
    public void reservePawn() {
        ((IServerFortressManager) this.fortressManagerSupplier.get())
                .getPawnWithoutAProfession()
                .ifPresent(IProfessional::reserve);
    }

    public void sendProfessions(@NotNull ServerPlayerEntity player) {
        initProfessionsIfNeeded();
        final var packet = new ClientboundProfessionsInitPacket(professionsInfos, professionsTree);
        FortressServerNetworkHelper.send(player, FortressChannelNames.FORTRESS_PROFESSION_INIT, packet);
    }

    private void initProfessionsIfNeeded() {
        if(getProfessions().isEmpty()) {
            final var professionsReader = new ProfessionsReader(server);
            professionsInfos = professionsReader.readProfessions();
            professionsTree = professionsReader.readTreeJson();
            final var professionsMap = professionsInfos
                    .stream()
                    .collect(Collectors.toMap(ProfessionFullInfo::key, it -> (IProfession)new Profession(it)));
            setProfessions(professionsMap);
            profToEntityMapper.read(server);
        }
    }

    public EntityType<? extends LivingEntity> getEntityTypeForProfession(String professionId) {
        return profToEntityMapper.getEntityTypeForProfession(professionId);
    }

    public void scheduleSync() {
        needsUpdate = true;
    }

    @Override
    public void write(NbtCompound tag){
        NbtCompound professionTag = new NbtCompound();
        getProfessions().forEach((key, value) -> professionTag.put(key, value.toNbt()));
        tag.put("profession", professionTag);
    }

    @Override
    public void read(NbtCompound tag) {
        if (tag.contains("profession")) {
            NbtCompound professionTag = tag.getCompound("profession");
            initProfessionsIfNeeded();
            for(String key : professionTag.getKeys()){
                final IProfession profession = super.getProfession(key);
                if(profession == null) continue;
                profession.readNbt(professionTag.getCompound(key));
                scheduleSync();
            }
        }

    }

    public Optional<String> getProfessionsWithAvailablePlaces(boolean professionRequiresReservation) {
        // All professions require reservation now
        if (!professionRequiresReservation) return Optional.empty();
        
        for(Map.Entry<String, IProfession> entry : getProfessions().entrySet()) {
            final String professionId = entry.getKey();
            final IProfession profession = entry.getValue();

            if(profession.getAmount() > 0) {
                final long colonistsWithProfession = countPawnsWithProfession(professionId);
                if(colonistsWithProfession < profession.getAmount()) {
                    return Optional.of(professionId);
                }
            }
        }
        return Optional.empty();
    }

    private long countPawnsWithProfession(String professionId) {
        final var fortressServerManager = (ServerFortressManager) super.fortressManagerSupplier.get();
        return fortressServerManager
                .getProfessionals()
                .stream()
                .filter(colonist -> colonist.getProfessionId().equals(professionId))
                .count();
    }

}
