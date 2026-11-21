package com.lambda.emcbaubles;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import moze_intel.projecte.emc.DecimalEmc;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

final class BuildWand extends Item {
    private static final String[] MODES = {"sample", "erase", "fill", "replace", "fill_replace", "picked"};
    private static final String[] SHAPES = {"box", "frame", "round", "ring", "triangle", "triangle_edge", "line"};
    private static final int MAX_CELLS = 16384;
    private static final int MAX_DISTANCE = 64;

    BuildWand() {
        super(new Properties().stacksTo(1));
    }

    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<net.minecraft.network.chat.Component> lines, TooltipFlag flag) {
        CompoundTag state = data(stack);
        lines.add(EmcBaubles.text("wand.state", EmcBaubles.text("mode." + MODES[mode(state)]),
                EmcBaubles.text("shape." + SHAPES[shape(state)])));
        lines.add(EmcBaubles.text("wand.hint").withStyle(ChatFormatting.GRAY));
    }

    private static int mode(CompoundTag state) {
        return Math.floorMod(state.getInt("Mode"), MODES.length);
    }

    private static int shape(CompoundTag state) {
        return Math.floorMod(state.getInt("Shape"), SHAPES.length);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                   InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            CompoundTag state = data(stack);
            if (player.isShiftKeyDown()) state.putInt("Mode", (mode(state) + 1) % MODES.length);
            else state.putInt("Shape", (shape(state) + 1) % SHAPES.length);
            clearPoints(state);
            CustomData.set(DataComponents.CUSTOM_DATA, stack, state);
            player.displayClientMessage(EmcBaubles.text("wand.state", EmcBaubles.text("mode." + MODES[mode(state)]),
                    EmcBaubles.text("shape." + SHAPES[shape(state)])), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        ItemStack wand = context.getItemInHand();
        CompoundTag state = data(wand);
        int mode = mode(state);
        int shape = shape(state);
        BlockPos clicked = context.getClickedPos();
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(state.getString("Dimension"))) clearPoints(state);
        state.putString("Dimension", dimension);
        if (mode == 0 || player.isShiftKeyDown()) {
            BlockState sample = level.getBlockState(clicked);
            if (!validSample(sample, level, clicked)) return InteractionResult.FAIL;
            state.put("Sample", NbtUtils.writeBlockState(sample));
            clearPoints(state);
            CustomData.set(DataComponents.CUSTOM_DATA, wand, state);
            player.displayClientMessage(EmcBaubles.text("wand.sampled", sample.getBlock().getName()), true);
            return InteractionResult.CONSUME;
        }

        int needed = shape == 4 || shape == 5 ? 3 : 2;
        int points = Math.clamp(state.getInt("Points"), 0, needed);
        if (points < needed) {
            state.putLong("Point" + points, clicked.asLong());
            state.putInt("Points", points + 1);
            CustomData.set(DataComponents.CUSTOM_DATA, wand, state);
            player.displayClientMessage(EmcBaubles.text("wand.point", points + 1, needed, clicked.toShortString()), true);
            return InteractionResult.CONSUME;
        }
        BlockPos start = BlockPos.of(state.getLong("Point0"));
        BlockPos end = BlockPos.of(state.getLong("Point1"));
        BlockPos third = needed == 3 ? BlockPos.of(state.getLong("Point2")) : start;
        clearPoints(state);
        CustomData.set(DataComponents.CUSTOM_DATA, wand, state);
        if (!near(player, start) || !near(player, end) || !near(player, third)
                || !level.hasChunkAt(start) || !level.hasChunkAt(end) || !level.hasChunkAt(third)) {
            player.displayClientMessage(EmcBaubles.text("wand.range"), true);
            return InteractionResult.FAIL;
        }

        BlockState replacement = Blocks.AIR.defaultBlockState();
        if (mode == 5) {
            if (!state.contains("Sample")) return InteractionResult.FAIL;
            replacement = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), state.getCompound("Sample"));
        } else if (mode != 1) {
            BlockState first = level.getBlockState(start);
            BlockState last = level.getBlockState(end);
            if (!EmcBaubles.ONE_SAMPLE.get() && first.getBlock() != last.getBlock()) {
                player.displayClientMessage(EmcBaubles.text("wand.mismatch"), true);
                return InteractionResult.FAIL;
            }
            replacement = EmcBaubles.ONE_SAMPLE.get() && EmcBaubles.USE_END.get() ? last : first;
        }
        if (mode != 1 && !validSample(replacement, level, start)) return InteractionResult.FAIL;
        doWork(level, player, start, end, third, mode, shape, replacement);
        return InteractionResult.CONSUME;
    }

    private static void clearPoints(CompoundTag state) {
        for (int i = 0; i < 3; i++) state.remove("Point" + i);
        state.remove("Points");
    }

    private static boolean near(ServerPlayer player, BlockPos pos) {
        return pos.distToCenterSqr(player.getX(), player.getY(), player.getZ()) <= MAX_DISTANCE * MAX_DISTANCE;
    }

    private static boolean validSample(BlockState block, Level level, BlockPos pos) {
        return !block.isAir() && !block.hasBlockEntity() && block.getFluidState().isEmpty()
                && block.getDestroySpeed(level, pos) >= 0 && !listed(block, EmcBaubles.SAMPLE_SKIP.get());
    }

    private static boolean listed(BlockState state, List<? extends String> values) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return values.stream().map(ResourceLocation::parse)
                .anyMatch(key -> key.equals(id) || state.is(BlockTags.create(key)));
    }

    private static void doWork(ServerLevel level, ServerPlayer player, BlockPos start, BlockPos end, BlockPos third,
                               int mode, int shape, BlockState replacement) {
        var knowledge = EmcBaubles.knowledge(player);
        if (knowledge == null) return;
        BigInteger price = mode == 1 ? BigInteger.ZERO : DecimalEmc.value(new ItemStack(replacement.getBlock()));
        if (mode != 1 && price.signum() <= 0) {
            player.displayClientMessage(EmcBaubles.text("wand.no_price"), true);
            return;
        }
        Set<BlockPos> target = positions(start, end, third, shape);
        if (target.isEmpty()) {
            player.displayClientMessage(EmcBaubles.text("wand.range"), true);
            return;
        }
        BigInteger change = BigInteger.ZERO;
        int changed = 0;
        for (BlockPos pos : target) {
            if (!level.hasChunkAt(pos) || !level.isInWorldBounds(pos) || !level.mayInteract(player, pos)) continue;
            BlockState previous = level.getBlockState(pos);
            if (!canChange(level, pos, previous, replacement, mode)) continue;
            BigInteger credit = previous.isAir() ? BigInteger.ZERO : DecimalEmc.sellValue(new ItemStack(previous.getBlock()));
            BigInteger cost = price.subtract(credit);
            BigInteger balance = DecimalEmc.balance(knowledge);
            if (balance.compareTo(cost) < 0) continue;
            if (!previous.isAir() && NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, previous, player)).isCanceled()) {
                continue;
            }
            if (!replacement.isAir() && !replacement.canSurvive(level, pos)) continue;
            var snapshot = BlockSnapshot.create(level.dimension(), level, pos);
            if (!level.setBlock(pos, replacement, Block.UPDATE_CLIENTS)) continue;
            if (!replacement.isAir() && NeoForge.EVENT_BUS.post(
                    new BlockEvent.EntityPlaceEvent(snapshot, previous, player)).isCanceled()) {
                level.setBlock(pos, previous, Block.UPDATE_CLIENTS);
                continue;
            }
            DecimalEmc.setBalance(knowledge, DecimalEmc.balance(knowledge).subtract(cost));
            if (!previous.isAir() && credit.signum() == 0 && EmcBaubles.DROP_UNVALUED.get()) {
                Block.dropResources(previous, level, pos, null, player, ItemStack.EMPTY);
            }
            level.updateNeighborsAt(pos, replacement.getBlock());
            changed++;
            change = change.subtract(cost);
        }
        if (changed > 0) knowledge.syncEmc(player);
        player.displayClientMessage(EmcBaubles.text("wand.done", changed, change.toString()), true);
    }

    private static boolean canChange(ServerLevel level, BlockPos pos, BlockState old, BlockState next, int mode) {
        if (old.equals(next) || old.hasBlockEntity() || !old.getFluidState().isEmpty()
                || listed(old, EmcBaubles.REPLACE_SKIP.get()) || old.getDestroySpeed(level, pos) < 0) return false;
        if (mode == 2) return old.isAir();
        if (mode == 1 || mode == 3) return !old.isAir();
        return true;
    }

    private static Set<BlockPos> positions(BlockPos a, BlockPos b, BlockPos c, int shape) {
        if (shape == 6) return line(a, b);
        if (shape == 5) {
            Set<BlockPos> edges = line(a, b);
            edges.addAll(line(b, c));
            edges.addAll(line(c, a));
            return edges;
        }
        BlockPos lo = new BlockPos(Math.min(a.getX(), Math.min(b.getX(), c.getX())),
                Math.min(a.getY(), Math.min(b.getY(), c.getY())), Math.min(a.getZ(), Math.min(b.getZ(), c.getZ())));
        BlockPos hi = new BlockPos(Math.max(a.getX(), Math.max(b.getX(), c.getX())),
                Math.max(a.getY(), Math.max(b.getY(), c.getY())), Math.max(a.getZ(), Math.max(b.getZ(), c.getZ())));
        int dx = hi.getX() - lo.getX() + 1;
        int dy = hi.getY() - lo.getY() + 1;
        int dz = hi.getZ() - lo.getZ() + 1;
        if ((long) dx * dy * dz > MAX_CELLS) return Set.of();
        Set<BlockPos> result = new LinkedHashSet<>();
        Vec3 ab = Vec3.atLowerCornerOf(b.subtract(a));
        Vec3 ac = Vec3.atLowerCornerOf(c.subtract(a));
        Vec3 normal = ab.cross(ac);
        for (BlockPos pos : BlockPos.betweenClosed(lo, hi)) {
            int x = pos.getX() - lo.getX();
            int y = pos.getY() - lo.getY();
            int z = pos.getZ() - lo.getZ();
            int faces = (edge(x, dx) ? 1 : 0) + (edge(y, dy) ? 1 : 0) + (edge(z, dz) ? 1 : 0);
            double radius = axis(x, dx) + axis(y, dy) + axis(z, dz);
            boolean keep = switch (shape) {
                case 1 -> faces >= 2;
                case 2 -> radius <= 1;
                case 3 -> radius <= 1 && radius >= 0.45;
                case 4 -> inTriangle(Vec3.atLowerCornerOf(pos.subtract(a)), ab, ac, normal);
                default -> true;
            };
            if (keep) result.add(pos.immutable());
        }
        return result;
    }

    private static boolean edge(int index, int size) {
        return index == 0 || index == size - 1;
    }

    private static double axis(int position, int size) {
        double delta = (2.0 * position + 1 - size) / size;
        return delta * delta;
    }

    private static boolean inTriangle(Vec3 point, Vec3 ab, Vec3 ac, Vec3 normal) {
        double areaSquared = normal.lengthSqr();
        if (areaSquared == 0 || Math.abs(point.dot(normal)) > 0.75 * Math.sqrt(areaSquared)) return false;
        double u = point.cross(ac).dot(normal) / areaSquared;
        double v = ab.cross(point).dot(normal) / areaSquared;
        return u >= -0.001 && v >= -0.001 && u + v <= 1.001;
    }

    private static Set<BlockPos> line(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), dz = b.getZ() - a.getZ();
        int length = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        Set<BlockPos> result = new LinkedHashSet<>();
        result.add(a);
        for (int i = 1; i <= length; i++) {
            double step = (double) i / length;
            result.add(a.offset((int) Math.round(step * dx), (int) Math.round(step * dy),
                    (int) Math.round(step * dz)));
        }
        return result;
    }
}
