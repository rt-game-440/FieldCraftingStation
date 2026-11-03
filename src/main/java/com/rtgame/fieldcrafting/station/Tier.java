package com.rtgame.fieldcrafting.station;

/**
 * 场域合成站的电压档位。耗电量都略低于该档电压的单个电流（LV 30 &lt; 32，MV 120 &lt; 128，依此类推）。
 */
public enum Tier {

    LV("lv", 32, 30, 3),
    MV("mv", 128, 120, 5),
    HV("hv", 512, 480, 8),
    EV("ev", 2048, 1920, 12),
    IV("iv", 8192, 7680, 16);

    /** 注册名与语言键里使用的小写档位名。 */
    public final String id;
    /** 该档电压（EU/包）。 */
    public final long voltage;
    /** 持续耗电（EU/t）。 */
    public final long usage;
    /** 场域范围半径（格）。 */
    public final int radius;

    Tier(String id, long voltage, long usage, int radius) {
        this.id = id;
        this.voltage = voltage;
        this.usage = usage;
        this.radius = radius;
    }

    /** 内部电量缓冲上限：4 个电流包。 */
    public long capacity() {
        return voltage * 4;
    }
}
