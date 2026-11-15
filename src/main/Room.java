package com.example.examplemod;

public final class Room {
    public final int id;
    public final Box box;
    /** Относительный вес шанса появления (по умолчанию 1). */
    public double chance = 1.0;
    /** Номер комнаты (по счёту генерации), на котором эта комната гарантированно появится. 0 = нет. */
    public int gar = 0;
    /** Если true - комната не выпадает случайно (гарант всё равно работает). */
    public boolean locked = false;

    public Room(int id, Box box) {
        this.id = id;
        this.box = box;
    }
}
