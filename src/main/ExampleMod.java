package com.example.examplemod;

import net.minecraftforge.fml.common.Mod;

@Mod(ExampleMod.MODID)
public final class ExampleMod {
    public static final String MODID = "examplemod";

    public ExampleMod() {
        RoomsEvents.register();
    }
}
