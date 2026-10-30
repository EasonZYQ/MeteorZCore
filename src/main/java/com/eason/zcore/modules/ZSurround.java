package com.eason.zcore.modules;

import com.eason.zcore.MeteorZCore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Z Surround 模块。
 * <p>
 * 参考 GCore 的 GSurround：在自己脚下四周自动补放黑曜石，
 * 可选双层（四个斜角）与头部高度的顶盖，用来防止被敌人水晶/爆炸直接命中。
 */
public class ZSurround extends Module {
    /** 默认设置分组。 */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 两次放置之间的 tick 延迟。 */
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        // 设置项名称
        .name("place-delay")
        // 设置项说明
        .description("The tick delay between placements.")
        // 默认值
        .defaultValue(1)
        // 允许输入的数值范围
        .range(0, 10)
        // 构建出设置对象
        .build()
    );

    /** 是否补放四个斜角位置。 */
    private final Setting<Boolean> doubleLayer = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("double")
        // 设置项说明
        .description("Also places blocks on the four diagonal positions.")
        // 默认关闭
        .defaultValue(false)
        // 构建出设置对象
        .build()
    );

    /** 是否在头部高度补放顶盖。 */
    private final Setting<Boolean> top = sgGeneral.add(new BoolSetting.Builder()
        // 设置项名称
        .name("top")
        // 设置项说明
        .description("Also places blocks at head height.")
        // 默认开启
        .defaultValue(true)
        // 构建出设置对象
        .build()
    );

    /** 剩余延迟 tick。 */
    private int delayLeft;

    /**
     * 构造方法，完成模块注册信息。
     */
    public ZSurround() {
        // 调用父类构造：传入分类、模块名与描述
        super(MeteorZCore.CATEGORY, "z-surround", "Places obsidian around your feet to block crystal damage.");
    }

    /**
     * 模块启用时调用，重置延迟。
     */
    @Override
    public void onActivate() {
        // 重置剩余延迟
        delayLeft = 0;
    }

    /**
     * 每个客户端 tick 开始时调用，按顺序补放环绕方块。
     *
     * @param event tick 前事件
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 玩家或世界不存在时直接返回
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // 生成需要放置的位置列表
        List<BlockPos> positions = buildPositions();
        // 已经全部放好时直接返回
        if (positions.isEmpty()) return;

        // 延迟未结束时先等待
        if (delayLeft > 0) {
            delayLeft--;
            return;
        }

        // 查找快捷栏中的黑曜石
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        // 没有黑曜石时不处理
        if (!obsidian.found()) return;

        // 放置第一个候选位置
        BlockPos pos = positions.getFirst();
        // 先挖掉挡路的可破坏方块
        if (!mc.level.getBlockState(pos).canBeReplaced() && BlockUtils.canBreak(pos)) {
            BlockUtils.breakBlock(pos, true);
            delayLeft = placeDelay.get();
            return;
        }

        // 放置黑曜石
        BlockUtils.place(pos, obsidian, false, 50, true, false, true);
        // 重置延迟
        delayLeft = placeDelay.get();
    }

    /**
     * 构建需要补放的方块位置列表。
     *
     * @return 需要放置的位置
     */
    private List<BlockPos> buildPositions() {
        // 以玩家脚下方块为基准
        BlockPos feet = mc.player.blockPosition();
        List<BlockPos> result = new ArrayList<>();

        // 四个相邻方向
        BlockPos[] sides = {
            feet.north(),
            feet.south(),
            feet.east(),
            feet.west()
        };
        for (BlockPos side : sides) {
            addIfNeeded(result, side);
        }

        // 双层模式补放四个斜角
        if (doubleLayer.get()) {
            addIfNeeded(result, feet.north().east());
            addIfNeeded(result, feet.north().west());
            addIfNeeded(result, feet.south().east());
            addIfNeeded(result, feet.south().west());
        }

        // 顶盖模式补放头部高度的四个方向
        if (top.get()) {
            for (BlockPos side : sides) {
                addIfNeeded(result, side.above());
            }
        }

        return result;
    }

    /**
     * 位置需要补放时加入列表。
     *
     * @param list 结果列表
     * @param pos  候选位置
     */
    private void addIfNeeded(List<BlockPos> list, BlockPos pos) {
        // 方块状态
        var state = mc.level.getBlockState(pos);
        // 已经是黑曜石或基岩时跳过
        if (state.is(net.minecraft.world.level.block.Blocks.OBSIDIAN) || state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) return;
        // 位置必须可以放置，或者可以挖开后放置
        if (!state.canBeReplaced() && !BlockUtils.canBreak(pos)) return;
        // 位置可以被替换但无法正常放置时跳过
        if (state.canBeReplaced() && !BlockUtils.canPlace(pos)) return;

        // 加入待放置列表
        list.add(pos);
    }
}
