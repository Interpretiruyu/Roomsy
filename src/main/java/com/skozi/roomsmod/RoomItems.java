package com.skozi.roomsmod;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

public final class RoomItems {
    private RoomItems() {}

    public static final String K_STICK = "rooms_stick";
    public static final String K_ENTRANCE = "rooms_entrance";
    public static final String K_EXIT = "rooms_exit";
    public static final String K_DOOR = "rooms_gendoor";
    public static final String K_SIGN = "rooms_sign";

    public static final Map<String, Item> DOORS = new LinkedHashMap<>();
    public static final Map<String, Item> SIGNS = new LinkedHashMap<>();

    static {
        DOORS.put("oak", Items.OAK_DOOR);
        DOORS.put("spruce", Items.SPRUCE_DOOR);
        DOORS.put("birch", Items.BIRCH_DOOR);
        DOORS.put("jungle", Items.JUNGLE_DOOR);
        DOORS.put("acacia", Items.ACACIA_DOOR);
        DOORS.put("dark_oak", Items.DARK_OAK_DOOR);
        DOORS.put("mangrove", Items.MANGROVE_DOOR);
        DOORS.put("cherry", Items.CHERRY_DOOR);
        DOORS.put("pale_oak", Items.PALE_OAK_DOOR);
        DOORS.put("bamboo", Items.BAMBOO_DOOR);
        DOORS.put("crimson", Items.CRIMSON_DOOR);
        DOORS.put("warped", Items.WARPED_DOOR);
        DOORS.put("iron", Items.IRON_DOOR);

        SIGNS.put("oak", Items.OAK_SIGN);
        SIGNS.put("spruce", Items.SPRUCE_SIGN);
        SIGNS.put("birch", Items.BIRCH_SIGN);
        SIGNS.put("jungle", Items.JUNGLE_SIGN);
        SIGNS.put("acacia", Items.ACACIA_SIGN);
        SIGNS.put("dark_oak", Items.DARK_OAK_SIGN);
        SIGNS.put("mangrove", Items.MANGROVE_SIGN);
        SIGNS.put("cherry", Items.CHERRY_SIGN);
        SIGNS.put("pale_oak", Items.PALE_OAK_SIGN);
        SIGNS.put("bamboo", Items.BAMBOO_SIGN);
        SIGNS.put("crimson", Items.CRIMSON_SIGN);
        SIGNS.put("warped", Items.WARPED_SIGN);
    }

    public static ItemStack named(Item item, String name, String key) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st -> st.withItalic(false)));
        CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.putBoolean(key, true));
        return s;
    }

    public static boolean has(ItemStack s, String key) {
        if (s == null || s.isEmpty()) return false;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        return cd != null && cd.copyTag().contains(key);
    }

    public static boolean isStick(ItemStack s) {
        return has(s, K_STICK);
    }

    public static RoomsManager.MarkerType markerOf(ItemStack s) {
        if (has(s, K_ENTRANCE)) return RoomsManager.MarkerType.ENTRANCE;
        if (has(s, K_EXIT)) return RoomsManager.MarkerType.EXIT;
        if (has(s, K_DOOR)) return RoomsManager.MarkerType.DOOR;
        if (has(s, K_SIGN)) return RoomsManager.MarkerType.SIGN;
        return null;
    }

    public static ItemStack stick() {
        return named(Items.STICK, "Rooms mod stick", K_STICK);
    }

    public static ItemStack entrance() {
        return named(Items.STRUCTURE_VOID, "Entrance", K_ENTRANCE);
    }

    public static ItemStack exit() {
        return named(Items.STRUCTURE_VOID, "Exit", K_EXIT);
    }

    public static ItemStack door(String type) {
        return named(DOORS.get(type), "Gen Door (" + type + ")", K_DOOR);
    }

    public static ItemStack sign(String type) {
        return named(SIGNS.get(type), "Rooms Sign (" + type + ")", K_SIGN);
    }
}
