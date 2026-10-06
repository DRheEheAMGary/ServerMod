package cn.dreamgary.hubsuite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 修原版 bug：{@code StructureTemplate$Palette} 的缓存**不是线程安全的**。
 *
 * <p>原版那个 {@code cache} 字段是 {@code Maps.newHashMap()}，{@code blocks(Block)}
 * 用 {@code HashMap.computeIfAbsent} 惰性填充它。但区块生成是**并行**的
 * （多个 {@code Worker-Main} 线程同时在跑 {@code ChunkGenerationTask}），
 * 两个线程同时对同一个结构模板的同一个 HashMap 调 {@code computeIfAbsent}，
 * 就会抛 {@code ConcurrentModificationException}。
 *
 * <p>实机崩溃栈（26.1.2，海岛维度生成沉船/海底废墟时）：
 * <pre>
 * java.util.ConcurrentModificationException
 *   at java.util.HashMap.computeIfAbsent
 *   at StructureTemplate$Palette.blocks(StructureTemplate.java:855)
 *   at StructureTemplate$Palette.jigsaws(StructureTemplate.java:843)
 *   at StructureTemplate.getJigsaws
 *   at SinglePoolElement.getShuffledJigsawBlocks
 *   at JigsawPlacement$Placer.tryPlacingChildren
 *   → ChunkMap.applyStep → ReportedException: Exception generating new chunk
 *   → 服务端直接崩溃退出（玩家一进海岛就可能触发）
 * </pre>
 *
 * <p>这是**上游已知问题**（MC-271899 "StructureTemplate Palette's caches are not thread safe"），
 * Paper 与 Canvas 都各自修过（换成线程安全的 Map / 缓存结构）。这里采用同一思路，
 * 但用 {@code @WrapOperation} 精确替换那一次 {@code computeIfAbsent}：
 * 用 {@code get} + {@code putIfAbsent} 复现它的语义 ——
 * 并发下只有一个线程能写入，另一个直接复用已写入的值，不再触碰 HashMap 的 modCount。
 *
 * <p><b>为什么不用"把 HashMap 换成 ConcurrentHashMap"那种更直白的改法：</b>
 * {@code Maps.newHashMap()} 在字节码里的静态返回类型是 {@code HashMap}，
 * 且后面直接 {@code putfield cache:Map}（**没有 CHECKCAST**），
 * 所以注入点必须原样返回 {@code HashMap} —— 而 {@code ConcurrentHashMap} 并不是
 * {@code HashMap} 的子类，那样改会直接验证失败。拦 {@code computeIfAbsent} 没有这个类型约束。
 *
 * <p>为什么这个模组必须自己修：海岛维度挂的是原版海洋结构（沉船 / 海底废墟），
 * 正是拼图（jigsaw）结构，玩家一进海岛开始生成区块就可能踩到，
 * 表现为"服务端毫无征兆地崩溃退出"。修在这里，玩家不需要额外装模组。
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteCacheMixin {

    /**
     * 把那次不安全的 {@code computeIfAbsent} 换成线程安全的等价实现。
     *
     * <p>语义对齐原版：
     * <ul>
     *   <li>已缓存 → 直接返回缓存（原版 {@code computeIfAbsent} 也是先查再算）；</li>
     *   <li>未缓存 → 算一次，然后 {@code putIfAbsent}（并发下只有一个线程写入）；</li>
     *   <li>算出来是 {@code null} → **不写入**（原版 {@code computeIfAbsent} 对 null 结果不记录）。</li>
     * </ul>
     */
    @WrapOperation(
            method = "blocks(Lnet/minecraft/world/level/block/Block;)Ljava/util/List;",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object hubsuite$threadSafeComputeIfAbsent(Map<Object, Object> map, Object key,
                                                      Function<Object, Object> mappingFunction,
                                                      Operation<Object> original) {
        Object cached = map.get(key);
        if (cached != null) {
            return cached;
        }
        Object computed = mappingFunction.apply(key);
        if (computed == null) {
            return null;   // 与原版一致：null 结果不进缓存
        }
        try {
            Object existing = map.putIfAbsent(key, computed);
            return existing != null ? existing : computed;
        } catch (Throwable t) {
            /*
             * 兜底：底层仍是 HashMap，putIfAbsent 是"默认方法"，并发下理论上
             * 仍可能因扩容而抛异常。这时**放弃缓存**、直接用算出来的值 ——
             * 代价是下次再算一遍（模板解析很快），但绝不让服务端因为这个崩掉。
             * 原版正是在这里抛 ConcurrentModificationException 把整个服务端带崩的。
             */
            return computed;
        }
    }
}
