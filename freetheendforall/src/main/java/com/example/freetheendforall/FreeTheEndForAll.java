package com.example.freetheendforall;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Mod(FreeTheEndForAll.MOD_ID)
public class FreeTheEndForAll {
    public static final String MOD_ID = "freetheendforall";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The "Free the End" advancement (minecraft:end/kill_dragon). */
    private static final ResourceLocation FREE_THE_END = ResourceLocation.withDefaultNamespace("end/kill_dragon");

    /** Guards against re-entry, since granting to others fires the same event again. */
    private static boolean distributing = false;

    public FreeTheEndForAll() {
        NeoForge.EVENT_BUS.addListener(this::onAdvancementEarn);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
    }

    /** Someone just earned an advancement. If it's Free the End, share it. */
    private void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        if (distributing) return;
        if (!(event.getEntity() instanceof ServerPlayer earner)) return;
        if (!event.getAdvancement().id().equals(FREE_THE_END)) return;

        MinecraftServer server = earner.getServer();
        if (server == null) return;

        PendingGrants data = PendingGrants.get(server);
        Set<UUID> online = new HashSet<>();

        // Online players: grant immediately.
        runSilently(server, () -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                online.add(player.getUUID());
                grant(server, player);
            }
        });

        // Offline players who have played before: queue the grant for their next login.
        int queued = 0;
        for (UUID id : knownPlayers(server)) {
            if (!online.contains(id) && data.pending.add(id)) queued++;
        }
        data.setDirty();
        LOGGER.info("Free the End shared: {} online player(s) granted, {} offline player(s) queued.", online.size(), queued);

        server.getPlayerList().broadcastSystemMessage(
                Component.literal("The Ender Dragon has fallen! Everyone has been granted Free the End.")
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                false);
    }

    /** A queued player logs in: hand over the advancement. */
    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        PendingGrants data = PendingGrants.get(server);
        if (data.pending.remove(player.getUUID())) {
            data.setDirty();
            runSilently(server, () -> grant(server, player));
        }
    }

    /** Completes every remaining criterion of Free the End for this player. */
    private static void grant(MinecraftServer server, ServerPlayer player) {
        AdvancementHolder holder = server.getAdvancements().get(FREE_THE_END);
        if (holder == null) return;

        PlayerAdvancements advancements = player.getAdvancements();
        AdvancementProgress progress = advancements.getOrStartProgress(holder);
        if (progress.isDone()) return;

        List<String> remaining = new ArrayList<>();
        progress.getRemainingCriteria().forEach(remaining::add);
        for (String criterion : remaining) {
            advancements.award(holder, criterion);
        }
    }

    /** Runs a task with advancement chat spam turned off (toasts still show). */
    private static void runSilently(MinecraftServer server, Runnable task) {
        GameRules.BooleanValue rule = server.getGameRules().getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS);
        boolean old = rule.get();
        distributing = true;
        rule.set(false, server);
        try {
            task.run();
        } finally {
            rule.set(old, server);
            distributing = false;
        }
    }

    /** UUIDs of everyone who has a playerdata file, i.e. has played on this world. */
    private static Set<UUID> knownPlayers(MinecraftServer server) {
        Set<UUID> ids = new HashSet<>();
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        try (Stream<Path> files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                 .filter(n -> n.endsWith(".dat"))
                 .map(n -> n.substring(0, n.length() - 4))
                 .forEach(s -> {
                     try {
                         ids.add(UUID.fromString(s));
                     } catch (IllegalArgumentException ignored) {
                     }
                 });
        } catch (IOException e) {
            LOGGER.error("Could not read playerdata folder", e);
        }
        return ids;
    }

    /** Saved with the world so queued offline players survive restarts. */
    public static class PendingGrants extends SavedData {
        private static final String NAME = MOD_ID + "_pending";
        final Set<UUID> pending = new HashSet<>();

        static PendingGrants get(MinecraftServer server) {
            return server.overworld().getDataStorage()
                    .computeIfAbsent(new SavedData.Factory<>(PendingGrants::new, PendingGrants::load, null), NAME);
        }

        static PendingGrants load(CompoundTag tag, HolderLookup.Provider registries) {
            PendingGrants data = new PendingGrants();
            ListTag list = tag.getList("pending", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                try {
                    data.pending.add(UUID.fromString(list.getString(i)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (UUID id : pending) list.add(StringTag.valueOf(id.toString()));
            tag.put("pending", list);
            return tag;
        }
    }
}
