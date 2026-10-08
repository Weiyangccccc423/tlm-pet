package com.tlmpet.carrier.state;

import com.tlmpet.carrier.TlmPetCarrier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务端每玩家身份记录（设计文档 §12.4 的第二重存储）。
 * <p>
 * 之所以用 {@code SavedData} 而不是 capability：它随存档一起保存，
 * 所以玩家把存档拷到另一台机器时，"她在这个世界里"这件事跟着走，
 * 不依赖目标机器的 config 目录。<b>这是它相对 config 桥不可替代的价值。</b>
 * <p>
 * 存储位置沿用 TLM 自己的做法（{@code MaidWorldData.java:33-45}），
 * 挂在主世界的 {@code DimensionDataStorage} 上 —— 这样跨维度访问拿到的是同一份数据。
 *
 * @see MaidCarrierStateStore
 */
public class ServerMaidCarrierData extends SavedData {
    private static final String IDENTIFIER = "tlm_pet_carrier_state";
    private static final String NBT_PLAYERS = "Players";

    private final Map<UUID, MaidCarrierState> states = new HashMap<>();

    @Nullable
    public static ServerMaidCarrierData get(Level level) {
        if (!(level instanceof ServerLevel)) {
            return null;
        }
        ServerLevel overWorld = level.getServer().getLevel(Level.OVERWORLD);
        if (overWorld == null) {
            TlmPetCarrier.LOGGER.error("找不到主世界，无法访问女仆身份记录");
            return null;
        }
        return overWorld.getDataStorage()
                .computeIfAbsent(ServerMaidCarrierData::load, ServerMaidCarrierData::new, IDENTIFIER);
    }

    public Optional<MaidCarrierState> get(UUID playerId) {
        return Optional.ofNullable(this.states.get(playerId));
    }

    public void set(UUID playerId, MaidCarrierState state) {
        this.states.put(playerId, state);
        this.setDirty();
    }

    public void clear(UUID playerId) {
        if (this.states.remove(playerId) != null) {
            this.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag players = new CompoundTag();
        this.states.forEach((playerId, state) -> players.put(playerId.toString(), state.toNbt()));
        tag.put(NBT_PLAYERS, players);
        return tag;
    }

    public static ServerMaidCarrierData load(CompoundTag tag) {
        ServerMaidCarrierData data = new ServerMaidCarrierData();
        if (!tag.contains(NBT_PLAYERS, Tag.TAG_COMPOUND)) {
            return data;
        }
        CompoundTag players = tag.getCompound(NBT_PLAYERS);
        for (String key : players.getAllKeys()) {
            UUID playerId = parseUuid(key);
            if (playerId == null) {
                continue;
            }
            // 单条记录损坏时跳过它，而不是让整份数据加载失败 ——
            // 否则一个玩家的坏数据会让全服所有人失去女仆身份。
            MaidCarrierState.fromNbt(players.getCompound(key))
                    .ifPresent(state -> data.states.put(playerId, state));
        }
        return data;
    }

    @Nullable
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            TlmPetCarrier.LOGGER.error("女仆身份记录中存在非法玩家 UUID，已跳过：{}", raw, e);
            return null;
        }
    }
}
