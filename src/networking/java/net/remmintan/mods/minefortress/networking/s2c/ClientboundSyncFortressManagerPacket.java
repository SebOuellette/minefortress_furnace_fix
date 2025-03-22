package net.remmintan.mods.minefortress.networking.s2c;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import net.remmintan.mods.minefortress.core.interfaces.networking.FortressS2CPacket;

public class ClientboundSyncFortressManagerPacket implements FortressS2CPacket {

    private final int colonistsCount;
    private final BlockPos fortressPos;
    private final boolean connectedToTheServer;
    private final int maxColonistsCount;
    private final int reservedColonistsCount;

    public ClientboundSyncFortressManagerPacket(int colonistsCount,
                                                BlockPos fortressPos,
                                                boolean connectedToTheServer,
                                                int maxColonistsCount,
                                                int reservedColonistsCount) {
        this.colonistsCount = colonistsCount;
        this.fortressPos = fortressPos;
        this.connectedToTheServer = connectedToTheServer;
        this.maxColonistsCount = maxColonistsCount;
        this.reservedColonistsCount = reservedColonistsCount;
    }

    public ClientboundSyncFortressManagerPacket(PacketByteBuf buf) {
        this.colonistsCount = buf.readInt();
        final boolean centerExists = buf.readBoolean();
        if(centerExists)
            this.fortressPos = buf.readBlockPos();
        else
            this.fortressPos = null;

        this.maxColonistsCount = buf.readInt();
        this.connectedToTheServer = buf.readBoolean();
        this.reservedColonistsCount = buf.readInt();
    }

    @Override
    public void handle(MinecraftClient client) {
        getManagersProvider()
                .get_ClientFortressManager()
                .sync(
                    colonistsCount,
                    fortressPos,
                    this.connectedToTheServer,
                    this.maxColonistsCount,
                        reservedColonistsCount
                );
    }

    @Override
    public void write(PacketByteBuf buf) {
        buf.writeInt(colonistsCount);
        final boolean centerExists = fortressPos != null;
        buf.writeBoolean(centerExists);
        if(centerExists)
            buf.writeBlockPos(fortressPos);

        buf.writeInt(maxColonistsCount);
        buf.writeBoolean(connectedToTheServer);
        buf.writeInt(reservedColonistsCount);
    }
}
