package org.figuramc.figura.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.backend2.FSBFabric;
import org.figuramc.figura.backend2.FabricNetworking;
import org.figuramc.figura.commands.fabric.FiguraCommandsFabric;
import org.figuramc.figura.config.ConfigManager;
import org.figuramc.figura.server.PayloadWrapper;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.server.packets.Side;
import org.figuramc.figura.server.packets.handlers.s2c.Handlers;
import org.figuramc.figura.server.packets.handlers.s2c.S2CPacketHandler;
import org.figuramc.figura.utils.fabric.FiguraResourceListenerImpl;

public class FiguraModFabric extends FiguraMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ConfigManager.init();
        onClientInit();
        FiguraCommandsFabric.init();
        // we cast here to the impl that implements synchronous as the manager wants
        // register reload listener
        ResourceLoader resourceLoader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        getResourceListeners().forEach(figuraResourceListener -> {
            var listener = (FiguraResourceListenerImpl)figuraResourceListener;
            resourceLoader.registerReloadListener(listener.getFabricId(), listener);
        });
        new FSBFabric();
        FabricNetworking.init(this::registerHandler, Side.CLIENT);
    }

    public <P extends Packet> void registerHandler(CustomPacketPayload.Type<PayloadWrapper<P>> type) {
        Identifier resLoc = type.id();
        S2CPacketHandler<P> handler = Handlers.getHandler(resLoc);
        if (handler != null) {
            ClientPlayNetworking.registerGlobalReceiver(type, new FabricClientHandler<>(handler));
        }
    }

    private static class FabricClientHandler<P extends Packet> implements ClientPlayNetworking.PlayPayloadHandler<PayloadWrapper<P>> {
        private final S2CPacketHandler<P> parent;

        private FabricClientHandler(S2CPacketHandler<P> parent) {
            this.parent = parent;
        }

        @Override
        public void receive(PayloadWrapper<P> payload, ClientPlayNetworking.Context context) {
            Minecraft.getInstance().execute(() -> parent.handle(payload.source()));
        }
    }
}
