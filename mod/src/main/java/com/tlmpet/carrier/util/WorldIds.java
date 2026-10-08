package com.tlmpet.carrier.util;

import net.minecraft.server.MinecraftServer;

/**
 * "世界"标识的统一取法。
 *
 * <h2>为什么用存档名而不是维度 ID</h2>
 * 玩家心里的"世界"是那个存档（"幻想乡"、"新整合包"），而不是 {@code minecraft:overworld}。
 * 这个值<b>只用于给玩家一句有用的提示</b>（"她还在「幻想乡」里"），不参与任何判定 ——
 * 判定一律走 {@code soulState}。所以即便两个存档重名，也只会让提示略显含糊，
 * 不会造成误判。
 *
 * <p>单独抽出来是因为原先 {@code MaidExtractor} / {@code MaidInjector} /
 * {@code MaidSingletonEvents} 各自抄了一份同样的方法体与同样的注释 —— 那条"为什么用存档名"
 * 的理由只应存在一处，否则将来改口径必然漏掉一份。
 */
public final class WorldIds {
    private WorldIds() {
    }

    public static String of(MinecraftServer server) {
        return server.getWorldData().getLevelName();
    }
}
