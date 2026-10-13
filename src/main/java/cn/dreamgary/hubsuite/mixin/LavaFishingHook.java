package cn.dreamgary.hubsuite.mixin;

/** 给 {@code FishingHook} 挂一个"这枚钩子是熔岩钓竿抛的"标记。 */
public interface LavaFishingHook {

    boolean hubsuite$fromLavaRod();

    void hubsuite$setFromLavaRod(boolean value);
}
