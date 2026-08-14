package tonton.pixelmonexpfixer.event;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.pixelmonmod.pixelmon.api.events.ExperienceGainEvent;
import com.pixelmonmod.pixelmon.api.events.battles.BattleEndEvent;
import com.pixelmonmod.pixelmon.api.events.battles.BattleStartedEvent;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.storage.PlayerPartyStorage;
import com.pixelmonmod.pixelmon.api.storage.StorageProxy;
import com.pixelmonmod.pixelmon.battles.controller.BattleController;
import com.pixelmonmod.pixelmon.battles.controller.participants.PlayerParticipant;

import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


public class PixelmonEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static class PokemonBattleData {
        final String playerName;
        final Pokemon pokemon;
        final int initialTotalXp;
        int accumulatedGainXp = 0;

        PokemonBattleData(String playerName, Pokemon pokemon) {
            this.playerName = playerName;
            this.pokemon = pokemon;
            this.initialTotalXp = calculateTotalExp(pokemon);
        }
    }

    private static final Map<BattleController, Map<UUID, PokemonBattleData>> activeBattles =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Queue<Runnable> pendingCompensations = new ConcurrentLinkedQueue<>();

    private static int calculateTotalExp(Pokemon pokemon) {
        int currentLevel = pokemon.getPokemonLevelContainer().getPokemonLevel();
        int baseExp = pokemon.getForm().getExperienceGroup().getExpForLevel(currentLevel);
        int gaugeExp = pokemon.getExperience();
        return baseExp + gaugeExp;
    }

    @SubscribeEvent
    public void onBattleStart(BattleStartedEvent event) {
        BattleController bc = event.getBattleController();
        if (bc == null) return;

        Map<UUID, PokemonBattleData> battleDataMap = new ConcurrentHashMap<>();

        for (Object playerObj : bc.getPlayers()) {
            if (playerObj instanceof PlayerParticipant participant) {
                Player player = participant.getPlayer();

                if (player != null) {
                    String playerName = player.getName().getString();
                    PlayerPartyStorage party = StorageProxy.getPartyNow(player.getUUID());

                    if (party != null) {
                        for (Pokemon pokemon : party.getAll()) {
                            if (pokemon != null) {
                                battleDataMap.put(pokemon.getUUID(), new PokemonBattleData(playerName, pokemon));
                            }
                        }
                    }
                }
            }
        }
        activeBattles.put(bc, battleDataMap);
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public void onExperience(ExperienceGainEvent event) {
        if (event.isCanceled()) return;

        BattleController bc = event.getBattleController();
        if (bc == null) return;

        Map<UUID, PokemonBattleData> battleDataMap = activeBattles.get(bc);
        if (battleDataMap != null) {
            PokemonBattleData data = battleDataMap.get(event.pokemon.getUUID());
            if (data != null) {
                data.accumulatedGainXp += event.getExperience();
            }
        }
    }

    @SubscribeEvent
    public void onBattleEnd(BattleEndEvent event) {
        BattleController bc = event.getBattleController();
        Map<UUID, PokemonBattleData> targetDataMap = activeBattles.remove(bc);

        if (targetDataMap == null || targetDataMap.isEmpty()) return;

        pendingCompensations.add(() -> {
            for (PokemonBattleData data : targetDataMap.values()) {
                Pokemon pokemon = data.pokemon;

                if (pokemon.getPokemonLevelContainer() == null) continue;

                int currentTotalXp = calculateTotalExp(pokemon);
                int expectedTotalXp = data.initialTotalXp + data.accumulatedGainXp;

                if (currentTotalXp < expectedTotalXp) {
                    int lostExp = expectedTotalXp - currentTotalXp;
                    pokemon.getPokemonLevelContainer().awardEXP(lostExp);

                    LOGGER.warn("[ExpFixer] Detected exp loss! Compensated {} exp for Player {}'s {}.",
                            lostExp, data.playerName, pokemon.getSpecies().getName());
                }
            }
        });
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!pendingCompensations.isEmpty()) {
            while (!pendingCompensations.isEmpty()) {
                Runnable task = pendingCompensations.poll();
                if (task != null) {
                    try {
                        task.run();
                    } catch (Exception e) {
                        LOGGER.error("[ExpFixer] Error while processing pending compensation: ", e);
                    }
                }
            }
        }
    }
}