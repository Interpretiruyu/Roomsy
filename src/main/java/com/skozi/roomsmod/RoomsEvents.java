package com.skozi.roomsmod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

public final class RoomsEvents {
    private RoomsEvents() {}

    public static void register() {
        RegisterCommandsEvent.BUS.addListener(RoomsEvents::onCommands);
        ServerStartingEvent.BUS.addListener(RoomsEvents::onStarting);
        ServerStoppingEvent.BUS.addListener(RoomsEvents::onStopping);
        PlayerInteractEvent.RightClickBlock.BUS.addListener(RoomsEvents::onRightClick);
        BlockEvent.BreakEvent.BUS.addListener(RoomsEvents::onBreak);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(RoomsEvents::onLogout);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(RoomsEvents::onLogin);
    }

    private static void onCommands(RegisterCommandsEvent e) {
        RoomsCommands.register(e.getDispatcher());
    }

    private static void onStarting(ServerStartingEvent e) {
        RoomsManager.load(e.getServer());
    }

    private static void onStopping(ServerStoppingEvent e) {
        RoomsManager.stopGeneration(); // при остановке сервера убираем сгенерированные комнаты
        RoomsManager.save();
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            RoomsManager.resetState(p);
            RoomsManager.onLogout(p);
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (RoomsManager.running && e.getEntity() instanceof ServerPlayer p) {
            RoomsManager.removeSticks(p);
        }
    }

    /** Возврат true = отменить событие. */
    private static boolean onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof Level lvl)) return false;
        RoomsManager.MarkerType t = RoomsManager.markerAt(lvl, e.getPos());
        // Entrance / Exit сломать нельзя - только ПКМ палкой
        return t == RoomsManager.MarkerType.ENTRANCE || t == RoomsManager.MarkerType.EXIT;
    }

    /** Возврат true = отменить событие. */
    private static boolean onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return false;
        if (!(e.getLevel() instanceof ServerLevel l)) return false;

        BlockPos pos = e.getPos();
        ItemStack held = e.getItemStack();

        // 1) мод-палка
        if (e.getHand() == InteractionHand.MAIN_HAND && RoomItems.isStick(held)) {
            if (RoomsManager.onStickClick(p, l, pos)) return true;
            return false;
        }

        // 2) предмет-маркер (Entrance/Exit/gendoor/sign): запоминаем, куда он будет поставлен
        RoomsManager.MarkerType mt = RoomItems.markerOf(held);
        if (mt != null) {
            if (e.getFace() != null) {
                BlockState clicked = l.getBlockState(pos);
                BlockPos target = clicked.canBeReplaced() ? pos : pos.relative(e.getFace());
                RoomsManager.putMarker(l, target, mt);
            }
            return false;
        }

        // 3) клик по gendoor во время генерации
        return RoomsManager.useGenDoor(p, l, pos);
    }
}
