package com.skozi.roomsmod;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class RoomsCommands {
    private RoomsCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("roomsset")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                .then(Commands.literal("reg").executes(c -> mode(c, RoomsManager.Mode.REG,
                        "Registration: right-click two blocks with the stick (opposite corners).")))
                .then(Commands.literal("regstart").executes(c -> mode(c, RoomsManager.Mode.REGSTART,
                        "Start room: right-click two blocks with the stick (opposite corners).")))
                .then(Commands.literal("kill").executes(c -> mode(c, RoomsManager.Mode.KILL,
                        "Right-click any block inside the room you want to delete from the list.")))
                .then(Commands.literal("cancel").executes(RoomsCommands::cancel))
                .then(Commands.literal("work").executes(RoomsCommands::work))
                .then(Commands.literal("stop").executes(RoomsCommands::stop))

                .then(Commands.literal("roomconfig")
                        .executes(c -> mode(c, RoomsManager.Mode.CONFIG,
                                "Config mode: right-click a block inside a room to select it."))
                        .then(Commands.literal("chance")
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
                                        .executes(RoomsCommands::cfgChance)))
                        .then(Commands.literal("gar")
                                .then(Commands.argument("room", IntegerArgumentType.integer(0))
                                        .executes(RoomsCommands::cfgGar)))
                        .then(Commands.literal("lock")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(RoomsCommands::cfgLock)))
                        .then(Commands.literal("done").executes(RoomsCommands::cancel)))

                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.literal("modstick").executes(RoomsCommands::giveStick))
                        .then(Commands.literal("entrance").executes(c -> giveSimple(c, RoomItems.entrance(), "Entrance")))
                        .then(Commands.literal("exit").executes(c -> giveSimple(c, RoomItems.exit(), "Exit")))
                        .then(Commands.literal("gendoor")
                                .then(Commands.argument("type", StringArgumentType.string())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(RoomItems.DOORS.keySet(), b))
                                        .executes(c -> giveTyped(c, true))))
                        .then(Commands.literal("sign")
                                .then(Commands.argument("type", StringArgumentType.string())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(RoomItems.SIGNS.keySet(), b))
                                        .executes(c -> giveTyped(c, false))))));
    }

    // ---------------- выдача предметов ----------------
    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    private static int giveStick(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<ServerPlayer> ts = EntityArgument.getPlayers(c, "targets");
        if (RoomsManager.running) {
            c.getSource().sendFailure(Component.literal("can't take modstick right now.").withStyle(ChatFormatting.RED));
            return 0;
        }
        for (ServerPlayer p : ts) give(p, RoomItems.stick());
        c.getSource().sendSuccess(() -> Component.literal("Gave Rooms mod stick to " + ts.size() + " player(s)."), true);
        return ts.size();
    }

    private static int giveSimple(CommandContext<CommandSourceStack> c, ItemStack proto, String name)
            throws CommandSyntaxException {
        Collection<ServerPlayer> ts = EntityArgument.getPlayers(c, "targets");
        for (ServerPlayer p : ts) give(p, proto.copy());
        c.getSource().sendSuccess(() -> Component.literal("Gave " + name + " to " + ts.size() + " player(s)."), true);
        return ts.size();
    }

    private static int giveTyped(CommandContext<CommandSourceStack> c, boolean door) throws CommandSyntaxException {
        Collection<ServerPlayer> ts = EntityArgument.getPlayers(c, "targets");
        String type = StringArgumentType.getString(c, "type").toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        Map<String, Item> map = door ? RoomItems.DOORS : RoomItems.SIGNS;
        if (!map.containsKey(type)) {
            c.getSource().sendFailure(Component.literal("Unknown type '" + type + "'. Available: "
                    + String.join(", ", map.keySet())).withStyle(ChatFormatting.RED));
            return 0;
        }
        for (ServerPlayer p : ts) give(p, door ? RoomItems.door(type) : RoomItems.sign(type));
        c.getSource().sendSuccess(() -> Component.literal("Gave " + (door ? "gendoor" : "sign") + " (" + type
                + ") to " + ts.size() + " player(s)."), true);
        return ts.size();
    }

    // ---------------- режимы палки ----------------
    /** Игрок с палкой в основной руке и не во время генерации. */
    private static ServerPlayer idlePlayer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        if (RoomsManager.running) {
            RoomsManager.red(p, "can't change rooms settings right now.");
            return null;
        }
        if (!RoomItems.isStick(p.getMainHandItem())) {
            RoomsManager.red(p, "Hold the Rooms mod stick in your main hand.");
            return null;
        }
        return p;
    }

    private static int mode(CommandContext<CommandSourceStack> c, RoomsManager.Mode m, String info)
            throws CommandSyntaxException {
        ServerPlayer p = idlePlayer(c);
        if (p == null) return 0;
        RoomsManager.PState s = RoomsManager.state(p);
        s.mode = m;
        s.p1 = null;
        s.p1dim = null;
        s.cfg = null;
        RoomsManager.yellow(p, info);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        RoomsManager.resetState(p);
        RoomsManager.yellow(p, "Mode cleared.");
        return 1;
    }

    // ---------------- roomconfig ----------------
    private static RoomsManager.PState cfgState(CommandContext<CommandSourceStack> c, ServerPlayer[] out)
            throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        out[0] = p;
        if (RoomsManager.running) {
            RoomsManager.red(p, "can't change rooms settings right now.");
            return null;
        }
        RoomsManager.PState s = RoomsManager.state(p);
        if (s.mode != RoomsManager.Mode.CONFIG || s.cfg == null) {
            RoomsManager.red(p, "Use /roomsset roomconfig and select a room with the stick first.");
            return null;
        }
        return s;
    }

    private static int cfgChance(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer[] p = new ServerPlayer[1];
        RoomsManager.PState s = cfgState(c, p);
        if (s == null) return 0;
        s.cfg.chance = DoubleArgumentType.getDouble(c, "value");
        RoomsManager.save();
        RoomsManager.yellow(p[0], "Room #" + s.cfg.id + ": chance = " + s.cfg.chance);
        return 1;
    }

    private static int cfgGar(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer[] p = new ServerPlayer[1];
        RoomsManager.PState s = cfgState(c, p);
        if (s == null) return 0;
        int n = IntegerArgumentType.getInteger(c, "room");
        if (n > 0) {
            for (Room r : RoomsManager.rooms) {
                if (r != s.cfg && r.gar == n) {
                    RoomsManager.red(p[0], "Room #" + r.id + " is already guaranteed at spawn number " + n + ".");
                    return 0;
                }
            }
        }
        s.cfg.gar = n;
        RoomsManager.save();
        RoomsManager.yellow(p[0], "Room #" + s.cfg.id + ": guaranteed at spawn number " + (n == 0 ? "none" : n));
        return 1;
    }

    private static int cfgLock(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer[] p = new ServerPlayer[1];
        RoomsManager.PState s = cfgState(c, p);
        if (s == null) return 0;
        s.cfg.locked = BoolArgumentType.getBool(c, "value");
        RoomsManager.save();
        RoomsManager.yellow(p[0], "Room #" + s.cfg.id + ": lock = " + s.cfg.locked);
        return 1;
    }

    // ---------------- генерация ----------------
    private static int work(CommandContext<CommandSourceStack> c) {
        String err = RoomsManager.startGeneration(c.getSource().getServer());
        if (err != null) {
            c.getSource().sendFailure(Component.literal(err).withStyle(ChatFormatting.RED));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("generation started.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        int n = RoomsManager.stopGeneration();
        if (n < 0) {
            c.getSource().sendFailure(Component.literal("generation is not started.").withStyle(ChatFormatting.RED));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("total blocks deleted: " + n).withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }
}
