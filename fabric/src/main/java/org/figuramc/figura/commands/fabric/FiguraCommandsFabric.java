package org.figuramc.figura.commands.fabric;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import org.figuramc.figura.commands.FiguraCommands;
import org.figuramc.figura.commands.LocalFcCommand;
import org.figuramc.figura.utils.FiguraClientCommandSource;

public class FiguraCommandsFabric {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        // register
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            CommandDispatcher<FiguraClientCommandSource> casted = (CommandDispatcher) dispatcher;
            casted.register(FiguraCommands.getCommandRoot());
            // 싱글 로컬 모드 /fc — 켜짐이 아니면 노드를 못 써서 서버 /fc 로 넘어간다(LocalFcCommand 머리 주석)
            casted.register(LocalFcCommand.getCommandRoot());
        });
    }
}
