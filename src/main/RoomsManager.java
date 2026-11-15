package com.skozi.roomsmod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.logging.LogUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;

/** Всё состояние мода: комнаты, маркеры, режимы игроков и сама генерация. */
public final class RoomsManager {
    private RoomsManager() {}

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    public enum MarkerType { ENTRANCE, EXIT, DOOR, SIGN }

    public enum Mode { NONE, REG, REGSTART, KILL, CONFIG }

    public static final class PState {
        public Mode mode = Mode.NONE;
        public BlockPos p1;
        public String p1dim;
        public Room cfg;
    }

    // Без обновления соседей/форм: двери и подобное не "отваливаются" при копировании.
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public static MinecraftServer server;
    public static final List<Room> rooms = new ArrayList<>();
    public static Room startRoom;
    private static int nextRoomId = 1;
    /** dim -> (pos -> тип маркера) */
    private static final Map<String, Map<Long, MarkerType>> markers = new HashMap<>();
    private static final Map<UUID, PState> states = new HashMap<>();

    // ---------------- генерация ----------------
    public static boolean running;
    private static ServerLevel genLevel;
    private static int counter;
    private static final List<Template> templates = new ArrayList<>();
    private static final List<Instance> instances = new ArrayList<>();
    private static final Map<Long, Instance> doorOwner = new HashMap<>();

    private record Cell(BlockPos rel, BlockState state, List<ItemStack> items) {}

    private static final class Template {
        Room room;
        BlockPos minRel, maxRel, exitRel;
        final List<BlockPos> doorsRel = new ArrayList<>();
        final List<BlockPos> signsRel = new ArrayList<>();
        final List<Cell> cells = new ArrayList<>();
    }

    private static final class Instance {
        boolean start;
        boolean spawned;
        int number;
        Box box;
        BlockPos exit;
        final List<BlockPos> doors = new ArrayList<>();
        final List<Long> written = new ArrayList<>();
    }

    // ---------------- сообщения ----------------
    public static void yellow(ServerPlayer p, String s) {
        p.sendSystemMessage(Component.literal(s).withStyle(ChatFormatting.YELLOW));
    }

    public static void red(ServerPlayer p, String s) {
        p.sendSystemMessage(Component.literal(s).withStyle(ChatFormatting.RED));
    }

    public static void green(ServerPlayer p, String s) {
        p.sendSystemMessage(Component.literal(s).withStyle(ChatFormatting.GREEN));
    }

    // ---------------- утилиты ----------------
    public static String dim(Level l) {
        return l.dimension().toString().replace(" ", "");
    }

    public static ServerLevel levelOf(String dim) {
        if (server == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            if (dim(l).equals(dim)) return l;
        }
        return null;
    }

    public static PState state(ServerPlayer p) {
        return states.computeIfAbsent(p.getUUID(), k -> new PState());
    }

    public static void resetState(ServerPlayer p) {
        states.remove(p.getUUID());
    }

    // ---------------- маркеры ----------------
    public static void putMarker(Level l, BlockPos p, MarkerType t) {
        markers.computeIfAbsent(dim(l), k -> new HashMap<>()).put(p.asLong(), t);
        save();
    }

    private static void removeMarker(Level l, BlockPos p) {
        Map<Long, MarkerType> m = markers.get(dim(l));
        if (m != null) m.remove(p.asLong());
        save();
    }

    private static boolean valid(Level l, BlockPos p, MarkerType t) {
        BlockState s = l.getBlockState(p);
        switch (t) {
            case ENTRANCE:
            case EXIT:
                return s.is(Blocks.STRUCTURE_VOID);
            case DOOR:
                return s.getBlock() instanceof DoorBlock
                        && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            case SIGN:
                return l.getBlockEntity(p) instanceof SignBlockEntity;
            default:
                return false;
        }
    }

    /** Тип маркера в позиции (только если блок там реально соответствует). */
    public static MarkerType markerAt(Level l, BlockPos p) {
        Map<Long, MarkerType> m = markers.get(dim(l));
        if (m == null) return null;
        MarkerType t = m.get(p.asLong());
        if (t == null || !valid(l, p, t)) return null;
        return t;
    }

    public static List<BlockPos> markersIn(Level l, Box b, MarkerType t) {
        List<BlockPos> out = new ArrayList<>();
        Map<Long, MarkerType> m = markers.get(dim(l));
        if (m == null) return out;
        for (Map.Entry<Long, MarkerType> e : m.entrySet()) {
            if (e.getValue() != t) continue;
            BlockPos p = BlockPos.of(e.getKey());
            if (b.contains(p) && valid(l, p, t)) out.add(p);
        }
        return out;
    }

    // ---------------- работа палки ----------------
    /** @return true - клик обработан (событие отменяется). */
    public static boolean onStickClick(ServerPlayer p, ServerLevel l, BlockPos pos) {
        PState s = state(p);
        MarkerType mt = markerAt(l, pos);
        boolean removable = (mt == MarkerType.ENTRANCE || mt == MarkerType.EXIT)
                && (s.mode == Mode.NONE || p.isShiftKeyDown());
        if (removable) {
            if (running) {
                red(p, "can't change rooms settings right now.");
                return true;
            }
            l.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            removeMarker(l, pos);
            yellow(p, (mt == MarkerType.ENTRANCE ? "Entrance" : "Exit") + " removed.");
            return true;
        }

        switch (s.mode) {
            case REG:
            case REGSTART: {
                if (running) {
                    red(p, "can't change rooms settings right now.");
                    s.mode = Mode.NONE;
                    return true;
                }
                if (s.p1 == null) {
                    s.p1 = pos;
                    s.p1dim = dim(l);
                    yellow(p, "Block 1 chosen");
                } else if (!s.p1dim.equals(dim(l))) {
                    red(p, "Both blocks must be in the same dimension.");
                } else if (pos.equals(s.p1)) {
                    red(p, "Can't chose one block two times.");
                } else {
                    boolean start = s.mode == Mode.REGSTART;
                    BlockPos a = s.p1;
                    s.p1 = null;
                    s.p1dim = null;
                    s.mode = Mode.NONE;
                    finishReg(p, l, a, pos, start);
                }
                return true;
            }
            case KILL: {
                if (running) {
                    red(p, "can't change rooms settings right now.");
                    s.mode = Mode.NONE;
                    return true;
                }
                Room found = roomAt(l, pos);
                if (found != null) {
                    rooms.remove(found);
                } else if (startRoom != null && startRoom.box.dim.equals(dim(l)) && startRoom.box.contains(pos)) {
                    startRoom = null;
                } else {
                    red(p, "There is no registered room here.");
                    return true;
                }
                save();
                s.mode = Mode.NONE;
                yellow(p, "room deleted from list.");
                return true;
            }
            case CONFIG: {
                if (running) {
                    red(p, "can't change rooms settings right now.");
                    s.mode = Mode.NONE;
                    return true;
                }
                Room r = roomAt(l, pos);
                if (r == null) {
                    red(p, "There is no registered room here (start room can't be configured).");
                    return true;
                }
                s.cfg = r;
                yellow(p, "Room #" + r.id + " selected: chance=" + r.chance + ", gar=" + r.gar + ", lock=" + r.locked);
                return true;
            }
            default:
                return false;
        }
    }

    private static Room roomAt(Level l, BlockPos pos) {
        String d = dim(l);
        for (Room r : rooms) {
            if (r.box.dim.equals(d) && r.box.contains(pos)) return r;
        }
        return null;
    }

    private static void finishReg(ServerPlayer p, ServerLevel l, BlockPos a, BlockPos b, boolean start) {
        Box box = Box.of(dim(l), a, b);
        for (Room r : rooms) {
            if (r.box.intersects(box)) {
                red(p, "Room intersects with another room (#" + r.id + ").");
                return;
            }
        }
        if (!start && startRoom != null && startRoom.box.intersects(box)) {
            red(p, "Room intersects with the start room.");
            return;
        }

        List<BlockPos> ent = markersIn(l, box, MarkerType.ENTRANCE);
        List<BlockPos> ex = markersIn(l, box, MarkerType.EXIT);
        List<BlockPos> doors = markersIn(l, box, MarkerType.DOOR);

        List<String> missing = new ArrayList<>();
        if (!start && ent.isEmpty()) missing.add("Entrance");
        if (ex.isEmpty()) missing.add("Exit");
        if (doors.isEmpty()) missing.add("Door");
        if (!missing.isEmpty()) {
            String head = start ? "start room must have Exit and Door. missing: "
                    : "room must have Entrance, Exit and Door. missing: ";
            red(p, head + String.join(", ", missing));
            return;
        }
        if (ent.size() > 1 || ex.size() > 1) {
            red(p, "Room must have exactly one Entrance and one Exit.");
            return;
        }

        if (start) {
            startRoom = new Room(nextRoomId++, box);
            save();
            green(p, "start room registered.");
        } else {
            rooms.add(new Room(nextRoomId++, box));
            save();
            green(p, "room successfully registered.");
        }
    }

    // ---------------- генерация: старт ----------------
    /** @return текст ошибки или null, если всё запущено. */
    public static String startGeneration(MinecraftServer srv) {
        if (running) return "generation is already started.";
        if (startRoom == null) return "start room is not registered.";
        if (rooms.isEmpty()) return "room list is empty.";
        server = srv;

        ServerLevel sl = levelOf(startRoom.box.dim);
        if (sl == null) return "start room dimension is not loaded.";
        List<BlockPos> sEx = markersIn(sl, startRoom.box, MarkerType.EXIT);
        List<BlockPos> sDoors = markersIn(sl, startRoom.box, MarkerType.DOOR);
        if (sEx.isEmpty() || sDoors.isEmpty()) return "start room must have Exit and Door.";

        List<Template> built = new ArrayList<>();
        for (Room r : rooms) {
            ServerLevel rl = levelOf(r.box.dim);
            if (rl == null) return "room #" + r.id + ": dimension is not loaded.";
            List<BlockPos> en = markersIn(rl, r.box, MarkerType.ENTRANCE);
            List<BlockPos> ex = markersIn(rl, r.box, MarkerType.EXIT);
            List<BlockPos> dr = markersIn(rl, r.box, MarkerType.DOOR);
            if (en.size() != 1 || ex.size() != 1 || dr.isEmpty()) {
                return "room #" + r.id + " must have Entrance, Exit and Door.";
            }
            built.add(snapshot(rl, r, en.get(0), ex.get(0), dr, markersIn(rl, r.box, MarkerType.SIGN)));
        }

        templates.clear();
        templates.addAll(built);
        instances.clear();
        doorOwner.clear();
        counter = 0;
        genLevel = sl;

        Instance st = new Instance();
        st.start = true;
        st.number = 0;
        st.box = startRoom.box;
        st.exit = sEx.get(0);
        for (BlockPos d : sDoors) {
            st.doors.add(d);
            doorOwner.put(d.asLong(), st);
        }
        instances.add(st);
        for (BlockPos sg : markersIn(sl, startRoom.box, MarkerType.SIGN)) writeSign(sl, sg, 0);

        running = true;
        states.clear();
        for (ServerPlayer pl : srv.getPlayerList().getPlayers()) removeSticks(pl);
        return null;
    }

    private static Template snapshot(ServerLevel l, Room r, BlockPos ent, BlockPos ex,
                                     List<BlockPos> doors, List<BlockPos> signs) {
        Template t = new Template();
        t.room = r;
        t.minRel = r.box.min().subtract(ent);
        t.maxRel = r.box.max().subtract(ent);
        t.exitRel = ex.subtract(ent);
        for (BlockPos d : doors) t.doorsRel.add(d.subtract(ent));
        for (BlockPos s : signs) t.signsRel.add(s.subtract(ent));

        for (int x = r.box.x1; x <= r.box.x2; x++) {
            for (int y = r.box.y1; y <= r.box.y2; y++) {
                for (int z = r.box.z1; z <= r.box.z2; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (p.equals(ent) || p.equals(ex)) continue; // клетки маркеров не копируем
                    BlockState st = l.getBlockState(p);
                    if (st.getBlock() instanceof DoorBlock && st.hasProperty(BlockStateProperties.OPEN)) {
                        st = st.setValue(BlockStateProperties.OPEN, false);
                    }
                    List<ItemStack> items = null;
                    BlockEntity be = l.getBlockEntity(p);
                    if (be instanceof Container c) {
                        items = new ArrayList<>();
                        for (int i = 0; i < c.getContainerSize(); i++) items.add(c.getItem(i).copy());
                    }
                    t.cells.add(new Cell(p.subtract(ent), st, items));
                }
            }
        }
        return t;
    }

    // ---------------- генерация: двери и спавн ----------------
    /** @return true - клик по двери обработан (событие отменяется). */
    public static boolean useGenDoor(ServerPlayer p, ServerLevel l, BlockPos clicked) {
        if (!running || l != genLevel) return false;
        BlockState st = l.getBlockState(clicked);
        if (!(st.getBlock() instanceof DoorBlock)) return false;
        BlockPos lower = st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                ? clicked.below() : clicked;
        Instance owner = doorOwner.get(lower.asLong());
        if (owner == null) return false;

        if (!owner.spawned) {
            if (!spawnNext(owner)) {
                red(p, "No room fits here (all candidates overlap other rooms).");
                return true;
            }
        }
        BlockState low = l.getBlockState(lower);
        if (low.hasProperty(BlockStateProperties.OPEN) && !low.getValue(BlockStateProperties.OPEN)) {
            setDoorOpen(l, lower, true);
        }
        return true; // закрыть дверь обратно нельзя
    }

    private static void setDoorOpen(ServerLevel l, BlockPos lower, boolean open) {
        BlockPos[] parts = { lower, lower.above() };
        for (BlockPos p : parts) {
            BlockState s = l.getBlockState(p);
            if (s.getBlock() instanceof DoorBlock && s.hasProperty(BlockStateProperties.OPEN)) {
                l.setBlock(p, s.setValue(BlockStateProperties.OPEN, open), FLAGS);
            }
        }
        if (open) {
            boolean iron = l.getBlockState(lower).is(Blocks.IRON_DOOR);
            l.levelEvent(null, iron ? 1005 : 1006, lower, 0);
        }
    }

    private static Box candidateBox(Template t, BlockPos base) {
        return Box.of(dim(genLevel), base.offset(t.minRel), base.offset(t.maxRel));
    }

    private static boolean fits(Template t, Instance from) {
        BlockPos base = from.exit;
        if (genLevel.isOutsideBuildHeight(base.offset(t.minRel)) || genLevel.isOutsideBuildHeight(base.offset(t.maxRel))) {
            return false;
        }
        Box cand = candidateBox(t, base);
        for (Instance i : instances) {
            if (i != from && i.box.intersects(cand)) return false;
        }
        for (Room r : rooms) {
            if (r.box.intersects(cand)) return false;
        }
        return true;
    }

    private static Template choose(Instance from, int number) {
        List<Template> fit = new ArrayList<>();
        for (Template t : templates) {
            if (fits(t, from)) fit.add(t);
        }
        for (Template t : fit) {
            if (t.room.gar == number) return t; // гарантированная комната
        }
        List<Template> pool = new ArrayList<>();
        double total = 0;
        for (Template t : fit) {
            if (!t.room.locked && t.room.chance > 0) {
                pool.add(t);
                total += t.room.chance;
            }
        }
        if (pool.isEmpty() || total <= 0) return null;
        double r = genLevel.getRandom().nextDouble() * total;
        for (Template t : pool) {
            r -= t.room.chance;
            if (r <= 0) return t;
        }
        return pool.get(pool.size() - 1);
    }

    private static boolean spawnNext(Instance from) {
        int number = counter + 1;
        Template t = choose(from, number);
        if (t == null) return false;

        BlockPos base = from.exit; // Entrance новой комнаты встаёт на Exit прошлой
        Instance in = new Instance();
        in.number = number;
        in.box = candidateBox(t, base);
        in.exit = base.offset(t.exitRel);

        for (Cell c : t.cells) {
            BlockPos p = base.offset(c.rel());
            genLevel.setBlock(p, c.state(), FLAGS);
            in.written.add(p.asLong());
            if (c.items() != null && genLevel.getBlockEntity(p) instanceof Container ct) {
                int n = Math.min(ct.getContainerSize(), c.items().size());
                for (int i = 0; i < n; i++) ct.setItem(i, c.items().get(i).copy());
            }
        }
        for (BlockPos r : t.doorsRel) {
            BlockPos d = base.offset(r);
            in.doors.add(d);
            doorOwner.put(d.asLong(), in);
        }
        for (BlockPos r : t.signsRel) writeSign(genLevel, base.offset(r), number);

        instances.add(in);
        counter = number;
        from.spawned = true;
        return true;
    }

    private static void writeSign(ServerLevel l, BlockPos p, int n) {
        BlockEntity be = l.getBlockEntity(p);
        if (be instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            text = text.setMessage(1, Component.literal(String.format("%03d", n)));
            sign.setText(text, true);
            sign.setChanged();
            BlockState st = l.getBlockState(p);
            l.sendBlockUpdated(p, st, st, 3);
        }
    }

    // ---------------- генерация: стоп ----------------
    /** @return количество удалённых блоков, либо -1 если генерация не запущена. */
    public static int stopGeneration() {
        if (!running) return -1;
        int deleted = 0;
        Instance start = null;
        for (Instance in : instances) {
            if (in.start) {
                start = in;
                continue;
            }
            for (long l : in.written) {
                BlockPos p = BlockPos.of(l);
                if (!genLevel.getBlockState(p).isAir()) deleted++;
                genLevel.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        if (start != null) {
            for (BlockPos d : start.doors) setDoorOpen(genLevel, d, false); // стартовую дверь закрываем обратно
        }
        instances.clear();
        doorOwner.clear();
        templates.clear();
        counter = 0;
        running = false;
        genLevel = null;
        return deleted;
    }

    public static void onLogout(ServerPlayer leaving) {
        if (!running || server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(leaving.getUUID())) return; // ещё кто-то онлайн
        }
        int n = stopGeneration();
        LOGGER.info("[Rooms] No players left, generation stopped. Total blocks deleted: {}", n);
    }

    public static void removeSticks(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (RoomItems.isStick(inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    // ---------------- сохранение ----------------
    private static Path file() {
        return server.getWorldPath(LevelResource.ROOT).resolve("roomsmod.txt");
    }

    public static void save() {
        if (server == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("N;" + nextRoomId);
        for (Map.Entry<String, Map<Long, MarkerType>> d : markers.entrySet()) {
            for (Map.Entry<Long, MarkerType> m : d.getValue().entrySet()) {
                lines.add("M;" + d.getKey() + ";" + m.getKey() + ";" + m.getValue().name());
            }
        }
        if (startRoom != null) lines.add(roomLine("S", startRoom));
        for (Room r : rooms) lines.add(roomLine("R", r));
        try {
            Files.write(file(), lines);
        } catch (IOException e) {
            LOGGER.error("[Rooms] Can't save data", e);
        }
    }

    private static String roomLine(String tag, Room r) {
        Box b = r.box;
        return tag + ";" + r.id + ";" + b.dim + ";" + b.x1 + ";" + b.y1 + ";" + b.z1 + ";"
                + b.x2 + ";" + b.y2 + ";" + b.z2 + ";" + r.chance + ";" + r.gar + ";" + r.locked;
    }

    public static void load(MinecraftServer srv) {
        server = srv;
        rooms.clear();
        markers.clear();
        states.clear();
        startRoom = null;
        nextRoomId = 1;
        running = false;
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] a = line.split(";");
                switch (a[0]) {
                    case "N" -> nextRoomId = Integer.parseInt(a[1]);
                    case "M" -> markers.computeIfAbsent(a[1], k -> new HashMap<>())
                            .put(Long.parseLong(a[2]), MarkerType.valueOf(a[3]));
                    case "R", "S" -> {
                        Box b = new Box(a[2], Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]),
                                Integer.parseInt(a[6]), Integer.parseInt(a[7]), Integer.parseInt(a[8]));
                        Room r = new Room(Integer.parseInt(a[1]), b);
                        r.chance = Double.parseDouble(a[9]);
                        r.gar = Integer.parseInt(a[10]);
                        r.locked = Boolean.parseBoolean(a[11]);
                        if (a[0].equals("S")) startRoom = r; else rooms.add(r);
                    }
                    default -> { }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[Rooms] Can't load data", e);
        }
    }
}
