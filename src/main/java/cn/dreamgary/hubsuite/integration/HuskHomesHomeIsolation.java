package cn.dreamgary.hubsuite.integration;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.ui.Text;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 让 HuskHomes 的「家」按**子服**隔离。
 *
 * <p><b>背景：</b>HuskHomes 的家是全局的（配置里没有 per-world 选项），
 * 而本模组每个子服是独立存档、各自一张地图。不隔离的话，生存服设的家
 * 到了创造服/空岛服照样能回，等于把几个子服又连起来了。
 *
 * <p><b>做法：</b>HuskHomes 的 Fabric 事件是可取消的，而且 {@code IHomeListEvent}
 * 允许直接替换列表。所以注册四个回调：
 * <ul>
 *   <li>{@code HomeListCallback} —— 只保留本子服的家（/homes 列表）；</li>
 *   <li>{@code TeleportCallback} —— 目标是别的子服的家时取消（/home、/phome）；</li>
 *   <li>{@code HomeDeleteCallback} / {@code HomeEditCallback} —— 别的子服的家不许删/改。</li>
 * </ul>
 *
 * <p><b>为什么整类都是反射：</b>事件和位置类都在 {@code huskhomes-common} 里，
 * 而 HuskHomes 的 Fabric 构件**没有把它声明成依赖**（它只是内嵌 jar），
 * 编译期拿不到；另外本模组的原则是"没装 HuskHomes 也不能崩"。
 * 所以这里只引用 Fabric API 与 Minecraft 的类，HuskHomes 全走反射
 * （和 {@link HuskHomesIntegration} 一致）。
 *
 * <p><b>归属判定：</b>HuskHomes 的世界名就是维度标识，用
 * {@code FabricHuskHomesAPI#getServerWorld(World)} 拿回 {@code ServerLevel}，
 * 再问 {@link WorldsManager#worldOfDimension} 得到场所 id。所以多维度子服
 * （空岛服的大厅/经典/海岛）算同一个子服。
 */
public final class HuskHomesHomeIsolation {

    private static final String API_CLASS = "net.william278.huskhomes.api.FabricHuskHomesAPI";
    private static final String CANCELLABLE = "net.william278.huskhomes.event.Cancellable";

    private static WorldsManager worlds;
    private static Object api;
    private static Class<?> homeClass;
    private static Class<?> worldClass;
    private static Class<?> onlineUserClass;

    private HuskHomesHomeIsolation() {
    }

    /** 由 {@link HuskHomesIntegration} 在检测到 HuskHomes 后反射调用。 */
    public static void register(WorldsManager worldsManager) {
        worlds = worldsManager;
        try {
            homeClass = Class.forName("net.william278.huskhomes.position.Home");
            worldClass = Class.forName("net.william278.huskhomes.position.World");
            onlineUserClass = Class.forName("net.william278.huskhomes.user.OnlineUser");

            bind("net.william278.huskhomes.event.HomeListCallback", HuskHomesHomeIsolation::onHomeList);
            bind("net.william278.huskhomes.event.TeleportCallback", HuskHomesHomeIsolation::onTeleport);
            bind("net.william278.huskhomes.event.HomeDeleteCallback", HuskHomesHomeIsolation::onHomeDelete);
            bind("net.william278.huskhomes.event.HomeEditCallback", HuskHomesHomeIsolation::onHomeEdit);

            HubSuite.logger().info("HuskHomes 已适配：传送指令全子服开启，"
                    + "「家」按子服隔离（列表 / 传送 / 删除 / 编辑）。");
        } catch (Throwable t) {
            HubSuite.logger().warn("HuskHomes 家隔离注册失败", t);
        }
    }

    // ------------------------------------------------------------------
    // 事件绑定（动态代理实现回调接口）
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface EventHandler {
        void handle(Object event) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static void bind(String callbackClassName, EventHandler handler) throws Exception {
        Class<?> callback = Class.forName(callbackClassName);
        Object busObject = callback.getField("EVENT").get(null);
        if (!(busObject instanceof Event<?>)) {
            return;
        }
        Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                (proxyObject, method, args) -> {
                    switch (method.getName()) {
                        case "invoke":
                            handler.handle(args == null || args.length == 0 ? null : args[0]);
                            // 取消由回调内部 setCancelled(true) 完成；
                            // 返回 PASS 让 HuskHomes 的 invokeEvents 去读 isCancelled()。
                            return InteractionResult.PASS;
                        case "toString":
                            return "HubSuiteHuskHomesHomeIsolation";
                        case "hashCode":
                            return System.identityHashCode(proxyObject);
                        case "equals":
                            return proxyObject == (args == null ? null : args[0]);
                        default:
                            return null;
                    }
                });
        ((Event<Object>) busObject).register(proxy);
    }

    // ------------------------------------------------------------------
    // 各事件
    // ------------------------------------------------------------------

    private static void onHomeList(Object event) throws Throwable {
        Optional<String> here = placeOfUser(iface("net.william278.huskhomes.event.IHomeListEvent",
                event, "getListViewer"));
        if (here.isEmpty()) {
            return;   // 不在任何场所（控制台等）→ 不过滤
        }
        Object raw = iface("net.william278.huskhomes.event.IHomeListEvent", event, "getHomes");
        if (!(raw instanceof List<?> homes)) {
            return;
        }
        List<Object> kept = new ArrayList<>();
        for (Object home : homes) {
            Optional<String> owner = placeOfHome(home);
            // 解析不出来就保留（宁可多显示，也别把玩家自己的家藏了）
            if (owner.isEmpty() || owner.get().equals(here.get())) {
                kept.add(stripPrefix(home, here.get()));
            }
        }
        Method setHomes = Class.forName("net.william278.huskhomes.event.IHomeListEvent")
                .getMethod("setHomes", List.class);
        setHomes.invoke(event, kept);
    }

    private static void onTeleport(Object event) throws Throwable {
        Object teleport = iface("net.william278.huskhomes.event.ITeleportEvent", event, "getTeleport");
        if (teleport == null) {
            return;
        }
        Object target = call(teleport, "getTarget");
        if (target == null || !homeClass.isInstance(target)) {
            return;   // 只管"回家"，不管 tpa / warp / spawn
        }
        if (isForeign(target, call(teleport, "getExecutor"), "回家")) {
            cancel(event);
        }
    }

    private static void onHomeDelete(Object event) throws Throwable {
        Object home = iface("net.william278.huskhomes.event.IHomeDeleteEvent", event, "getHome");
        Object deleter = iface("net.william278.huskhomes.event.IHomeDeleteEvent", event, "getDeleter");
        if (isForeign(home, deleter, "删除")) {
            cancel(event);
        }
    }

    private static void onHomeEdit(Object event) throws Throwable {
        Object home = iface("net.william278.huskhomes.event.IHomeEditEvent", event, "getHome");
        Object editor = iface("net.william278.huskhomes.event.IHomeEditEvent", event, "getEditor");
        if (isForeign(home, editor, "编辑")) {
            cancel(event);
        }
    }

    /** 家不在玩家当前子服 → 提示并返回 true（调用方负责取消事件）。 */
    private static boolean isForeign(Object home, Object commandUser, String action) {
        Optional<String> here = placeOfUser(commandUser);
        Optional<String> owner = placeOfHome(home);
        if (here.isEmpty() || owner.isEmpty() || owner.get().equals(here.get())) {
            return false;
        }
        ServerPlayer player = playerOf(commandUser);
        if (player != null) {
            notify(player, "\u00A7c这个家属于子服 \u00A7f" + owner.get()
                    + "\u00A7c，请在那边" + action + "。");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 归属解析
    // ------------------------------------------------------------------

    /** 玩家当前所在的场所 id（子服 id / "lobby"）。 */
    private static Optional<String> placeOfUser(Object commandUser) {
        ServerPlayer player = playerOf(commandUser);
        if (player == null || worlds == null) {
            return Optional.empty();
        }
        return worlds.worldOf(player).map(PlayableWorld::id);
    }

    /** 一个家所在的世界属于哪个场所。 */
    private static Optional<String> placeOfHome(Object home) {
        if (home == null || worlds == null) {
            return Optional.empty();
        }
        try {
            Object world = call(home, "getWorld");
            if (world == null) {
                return Optional.empty();
            }
            Object instance = api();
            if (instance == null) {
                return Optional.empty();
            }
            Method getServerWorld = Class.forName(API_CLASS).getMethod("getServerWorld", worldClass);
            Object level = getServerWorld.invoke(instance, world);
            if (!(level instanceof ServerLevel serverLevel)) {
                return Optional.empty();
            }
            return worlds.worldOfDimension(serverLevel.dimension()).map(PlayableWorld::id);
        } catch (Throwable t) {
            HubSuite.logger().debug("解析 HuskHomes 的家所属世界失败：{}", t.toString());
            return Optional.empty();
        }
    }

    private static ServerPlayer playerOf(Object commandUser) {
        if (commandUser == null || onlineUserClass == null || !onlineUserClass.isInstance(commandUser)) {
            return null;
        }
        try {
            Object instance = api();
            if (instance == null) {
                return null;
            }
            Method m = Class.forName(API_CLASS).getMethod("getServerPlayerEntity", onlineUserClass);
            Object player = m.invoke(instance, commandUser);
            return player instanceof ServerPlayer sp ? sp : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 懒取 HuskHomes API 实例。
     *
     * <p><b>为什么不能提前拿：</b>{@code onInitialize} 阶段 HuskHomes 还没 {@code enable}，
     * 它的 {@code FabricHuskHomesAPI.getInstance()} 会抛 {@code NotRegisteredException}
     * （实测：日志里一条 InvocationTargetException）。事件本身是静态的、可以早绑，
     * 但实例要等真正用到时再取。
     */
    private static Object api() {
        if (api == null) {
            try {
                api = Class.forName(API_CLASS).getMethod("getInstance").invoke(null);
            } catch (Throwable t) {
                return null;
            }
        }
        return api;
    }

    /**
     * 去掉家名前的子服前缀（`1_s1` → `s1`），只影响**显示**。
     *
     * <p>{@code Home} 没有 setter，所以按原坐标/所有者重新造一个改名的 Home；
     * 失败就照原样显示（宁可多俩字符，也不能让列表空掉）。
     */
    private static Object stripPrefix(Object home, String placeId) {
        try {
            String name = (String) call(home, "getName");
            String stripped = HomeNamespace.strip(placeId, name);
            if (stripped == null || stripped.equals(name)) {
                return home;
            }
            double x = (Double) call(home, "getX");
            double y = (Double) call(home, "getY");
            double z = (Double) call(home, "getZ");
            float yaw = (Float) call(home, "getYaw");
            float pitch = (Float) call(home, "getPitch");
            Object world = call(home, "getWorld");
            Object meta = call(home, "getMeta");
            Object owner = call(home, "getOwner");
            Object uuid = call(home, "getUuid");
            boolean isPublic = (Boolean) call(home, "isPublic");
            String description = meta == null ? "" : (String) call(meta, "getDescription");

            Class<?> metaClass = Class.forName("net.william278.huskhomes.position.PositionMeta");
            Object newMeta = metaClass.getMethod("create", String.class, String.class)
                    .invoke(null, stripped, description);
            Class<?> userClass = Class.forName("net.william278.huskhomes.user.User");
            Class<?> homeClass = Class.forName("net.william278.huskhomes.position.Home");
            Method from = homeClass.getMethod("from", double.class, double.class, double.class,
                    float.class, float.class, worldClass, String.class, metaClass,
                    java.util.UUID.class, userClass, boolean.class);
            return from.invoke(null, x, y, z, yaw, pitch, world, stripped, newMeta, uuid, owner, isPublic);
        } catch (Throwable t) {
            HubSuite.logger().debug("去掉家名前缀失败（照原样显示）：{}", t.toString());
            return home;
        }
    }

    private static void cancel(Object event) throws Exception {
        Method m = Class.forName(CANCELLABLE).getMethod("setCancelled", boolean.class);
        m.invoke(event, true);
    }

    private static Object iface(String interfaceName, Object target, String method) throws Exception {
        return Class.forName(interfaceName).getMethod(method).invoke(target);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    /** 提示回主线程发（传送事件可能异步）。 */
    private static void notify(ServerPlayer player, String message) {
        var server = HubSuite.worlds() == null ? null : HubSuite.worlds().server();
        if (server != null) {
            server.execute(() -> player.sendSystemMessage(Text.of(message)));
        } else {
            player.sendSystemMessage(Text.of(message));
        }
    }
}
