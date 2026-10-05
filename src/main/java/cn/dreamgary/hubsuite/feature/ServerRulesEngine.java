package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.PlayerRouter;
import cn.dreamgary.hubsuite.world.SubServer;
import cn.dreamgary.hubsuite.world.WorldsManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 各子服的通用玩法规则。
 *
 * <p>实现三件事：
 * <ol>
 *   <li><b>PvP / 无敌</b>：按玩家所在子服的规则拦截伤害（原版 gamerule 已经能挡大部分，
 *       这里补上"跨服边界"和 mod 自有开关的判定）；</li>
 *   <li><b>掉虚空保护</b>：任何子服里掉到世界底部以下就送回该子服的出生点
 *       （空岛服必需，其他服也顺手兜底）；</li>
 *   <li><b>位置记忆</b>：为每个"玩家 × 子服"记住退出位置，
 *       再次进入时回到原位（生存服的要求），可选开关。</li>
 * </ol>
 */
public final class ServerRulesEngine {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TypeToken<Map<String, Map<String, double[]>>> MEMORY_TYPE = new TypeToken<>() {
    };

    private final WorldsManager worlds;
    /** {玩家 UUID → {子服 id → [x, y, z, yaw, pitch]}} */
    private final Map<String, Map<String, double[]>> memory = new LinkedHashMap<>();
    private final Path memoryFile;

    public ServerRulesEngine(WorldsManager worlds) {
        this.worlds = worlds;
        this.memoryFile = FabricLoader.getInstance().getConfigDir()
                .resolve(HubSuite.MOD_ID).resolve("last-locations.json");
    }

    public void register() {
        loadMemory();

        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::allowDamage);
        ServerPlayerEvents.LEAVE.register(this::onLeave);

        // 切服时也要记位置：原版 LEAVE 只在断开服务器时触发，切服不触发
        PlayerRouter.addExitListener((player, world) -> {
            remember(player, world, player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
            // 立即落盘：切服是低频操作，写一次 JSON 的开销远小于"崩溃就丢位置"的代价
            saveMemory();
        });
        ServerPlayerEvents.AFTER_RESPAWN.register(this::onRespawn);

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 20 == 0) {
                checkVoid(server);
            }
            if (tickCounter % 6000 == 0) {
                saveMemory();
            }
        });
    }

    private int tickCounter;

    // ------------------------------------------------------------------
    // PvP / 无敌
    // ------------------------------------------------------------------

    private boolean allowDamage(net.minecraft.world.entity.LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer player)) {
            return true;
        }
        PlayableWorld world = worlds.worldOf(player).orElse(null);
        if (world == null) {
            return true;
        }
        var rules = world.rules();
        if (rules.invulnerable()) {
            return false;
        }
        // PvP：攻击者是玩家且不是自己
        if (source.getEntity() instanceof ServerPlayer attacker && attacker != player) {
            if (!rules.pvpAllowed()) {
                attacker.sendSystemMessage(Component.literal("\u00A7c该服务器已关闭玩家间伤害。"));
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 掉虚空保护
    // ------------------------------------------------------------------

    private void checkVoid(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayableWorld world = worlds.worldOf(player).orElse(null);
            if (world == null) {
                continue;
            }
            double floor = player.level().getMinY() - 8.0;
            if (player.getY() < floor) {
                PlayerRouter.sendTo(player, world);
                player.sendSystemMessage(Component.literal("\u00A7e你掉出了世界，已送回出生点。"));
            }
        }
    }

    // ------------------------------------------------------------------
    // 位置记忆
    // ------------------------------------------------------------------

    private void onLeave(ServerPlayer player) {
        PlayableWorld world = worlds.worldOf(player).orElse(null);
        if (world == null) {
            return;
        }
        remember(player, world, player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
        saveMemory();
    }

    /** 死亡重生：回到该子服记录的位置（生存服要求"回原位"）。 */
    private void onRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        PlayableWorld world = worlds.worldOf(oldPlayer).orElse(null);
        if (!(world instanceof SubServer sub) || !sub.config().rememberLastLocation) {
            return;
        }
        double[] spot = recall(oldPlayer.getUUID(), world.id());
        if (spot == null || spot.length < 5) {
            return;
        }
        newPlayer.level().getServer().execute(() -> {
            try {
                newPlayer.teleportTo(sub.level(), spot[0], spot[1], spot[2],
                        java.util.Set.of(), (float) spot[3], (float) spot[4], false);
            } catch (Throwable t) {
                HubSuite.logger().debug("重生回原位失败：{}", t.toString());
            }
        });
    }

    /**
     * 记录位置（下次进入同一个地方时优先使用记忆值）。
     *
     * <p><b>按维度记，不能按场所 id 记。</b>空岛服是一个"多维度子服"——
     * 大厅/经典空岛/海岛三个维度的 {@code id()} 都是 {@code skyblock}，
     * 用 id 当 key 会让三个维度共用一份坐标：从经典空岛切到海岛，
     * 玩家会被丢到"经典空岛里的那个坐标"上（用户实测反馈）。
     */
    public void remember(ServerPlayer player, PlayableWorld world,
                         double x, double y, double z, float yaw, float pitch) {
        String key = player.level().dimension().identifier().toString();
        memory.computeIfAbsent(player.getUUID().toString(), k -> new LinkedHashMap<>())
                .put(key, new double[]{x, y, z, yaw, pitch});
    }

    /** 取回记忆的位置；没有则返回 null。 */
    public double[] recall(UUID uuid, String worldId) {
        Map<String, double[]> perPlayer = memory.get(uuid.toString());
        if (perPlayer == null) {
            return null;
        }
        return perPlayer.get(worldId);
    }

    /**
     * 进入子服时的落点：记忆优先，其次配置出生点。
     *
     * @param dimension 这次要进入的**具体维度**；坐标记忆是按它存的
     *                  （多维度子服必须区分，见 {@link #remember}）
     */
    public cn.dreamgary.hubsuite.world.PlayableWorld.SpawnPoint resolveEntry(
            ServerPlayer player, SubServer sub,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        if (sub.config().rememberLastLocation && dimension != null) {
            double[] spot = recall(player.getUUID(), dimension.identifier().toString());
            if (spot != null && spot.length >= 5) {
                return new cn.dreamgary.hubsuite.world.PlayableWorld.SpawnPoint(
                        spot[0], spot[1], spot[2], (float) spot[3], (float) spot[4]);
            }
        }
        return sub.spawn();
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    private void loadMemory() {
        try {
            if (!Files.exists(memoryFile)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(memoryFile, StandardCharsets.UTF_8)) {
                Map<String, Map<String, double[]>> data = GSON.fromJson(reader, MEMORY_TYPE.getType());
                if (data != null) {
                    memory.putAll(data);
                }
            }
            HubSuite.logger().info("已载入位置记忆：{} 名玩家。", memory.size());
        } catch (Exception e) {
            HubSuite.logger().warn("载入位置记忆失败：{}", e.toString());
        }
    }

    public void saveMemory() {
        try {
            Files.createDirectories(memoryFile.getParent());
            Path tmp = memoryFile.resolveSibling("last-locations.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(memory, writer);
            }
            try {
                Files.move(tmp, memoryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException unsupported) {
                Files.move(tmp, memoryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            HubSuite.logger().error("保存位置记忆失败", e);
        }
    }

    public Path memoryFile() {
        return memoryFile;
    }
}
