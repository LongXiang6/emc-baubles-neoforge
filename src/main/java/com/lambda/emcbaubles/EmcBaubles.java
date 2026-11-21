package com.lambda.emcbaubles;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.api.proxy.IEMCProxy;
import moze_intel.projecte.emc.DecimalEmc;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

@Mod(EmcBaubles.ID)
public final class EmcBaubles {
    public static final String ID = "emcbaubles";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    private static final List<Supplier<? extends Item>> CONTENTS = new ArrayList<>();
    private static final String[] MATTERS = {
            "clay", "dark", "red", "magenta", "pink", "purple", "violet", "blue",
            "cyan", "green", "lime", "yellow", "orange", "white", "fading"
    };
    private static final long[] COLLECTORS = {
            4L, 12L, 40L, 160L, 640L, 2560L, 10240L, 40960L,
            163840L, 655360L, 2621440L, 10485760L, 41943040L, 167772160L, 671088640L, 2000000000L
    };
    private static final long[] FLOWERS = {
            102L, 306L, 1020L, 4080L, 16020L, 10240L, 68580L, 296820L,
            1187280L, 4749120L, 18996480L, 75985920L, 303953680L, 1215774720L, 4863098880L, 19452395520L
    };
    private static final DeferredItem<Item> BELT = add("emc_belt", () -> new Wearable("belt", 0));
    private static final DeferredItem<Item> REFILLER = add("emc_refiller", () -> new Wearable("refiller", 0));
    private static final DeferredItem<Item> WAND = add("emc_build_wand", BuildWand::new);
    private static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue FILL_FIRST;
    private static final ModConfigSpec.BooleanValue IGNORE_TOOLS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> BELT_SKIP;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> BELT_NEVER;
    static final ModConfigSpec.BooleanValue ONE_SAMPLE;
    static final ModConfigSpec.BooleanValue USE_END;
    static final ModConfigSpec.BooleanValue DROP_UNVALUED;
    static final ModConfigSpec.ConfigValue<List<? extends String>> SAMPLE_SKIP;
    static final ModConfigSpec.ConfigValue<List<? extends String>> REPLACE_SKIP;

    static {
        for (int i = 0; i < MATTERS.length; i++) {
            String name = MATTERS[i] + "_matter";
            if (i != 1 && i != 2) {
                add(name, () -> new Item(new Item.Properties()));
                block(name + "_block");
            }
            block("compressed_" + name + "_block");
        }
        for (int i = 0; i < COLLECTORS.length; i++) {
            int tier = i + 1;
            long collector = COLLECTORS[i];
            long charm = FLOWERS[i];
            add("collector_mk" + tier + "_necklace", () -> new Wearable("generator", collector));
            add("flower_mk" + tier + "_charm", () -> new Wearable("generator", charm));
        }
        TABS.register("emcbaublesctab", () -> CreativeModeTab.builder()
                .title(Component.translatable("emcbaubles.tab")).icon(() -> new ItemStack(WAND.get()))
                .displayItems((parameters, output) -> CONTENTS.forEach(item -> output.accept(item.get()))).build());

        ModConfigSpec.Builder config = new ModConfigSpec.Builder();
        config.push("Belt Settings");
        FILL_FIRST = config.define("fill_first", true);
        IGNORE_TOOLS = config.define("ignore_tools", true);
        BELT_SKIP = config.defineListAllowEmpty("blacklist", List.of("projecte:transmutation_table"), EmcBaubles::validId);
        BELT_NEVER = config.defineListAllowEmpty("true_blacklist", List.of("projecte:transmutation_tablet"), EmcBaubles::validId);
        config.pop().push("Build Wand Settings");
        ONE_SAMPLE = config.define("only_one_select", false);
        USE_END = config.define("select_end", false);
        DROP_UNVALUED = config.define("drop_non_emc_items", true);
        SAMPLE_SKIP = config.defineListAllowEmpty("start_blacklist", List.of("minecraft:chest"), EmcBaubles::validId);
        REPLACE_SKIP = config.defineListAllowEmpty("replace_blacklist",
                List.of("minecraft:bedrock", "minecraft:end_portal_frame", "minecraft:chest"), EmcBaubles::validId);
        SPEC = config.pop().build();
    }

    public EmcBaubles(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
        NeoForge.EVENT_BUS.addListener(EmcBaubles::pickup);
        NeoForge.EVENT_BUS.addListener(EmcBaubles::refill);
    }

    private static DeferredItem<Item> add(String name, Supplier<Item> item) {
        var registration = ITEMS.register(name, item);
        CONTENTS.add(registration);
        return registration;
    }

    private static void block(String name) {
        var block = BLOCKS.registerSimpleBlock(name, BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                .strength(0.5F, 1.5F));
        CONTENTS.add(ITEMS.registerSimpleBlockItem(name, block, new Item.Properties()));
    }

    private static boolean validId(Object candidate) {
        return candidate instanceof String text && ResourceLocation.tryParse(text) != null;
    }

    static IKnowledgeProvider knowledge(ServerPlayer player) {
        return PECapabilities.KNOWLEDGE_CAPABILITY.getCapability(player, null);
    }

    static void setBalance(ServerPlayer player, IKnowledgeProvider knowledge, BigInteger amount) {
        DecimalEmc.setBalance(knowledge, amount);
        knowledge.syncEmc(player);
    }

    static MutableComponent text(String key, Object... args) {
        return Component.translatable(ID + "." + key, args);
    }

    private static boolean equipped(ServerPlayer player, Item item) {
        return CuriosApi.getCuriosInventory(player).map(inventory -> inventory.isEquipped(item)).orElse(false);
    }

    private static boolean listed(ItemStack stack, List<? extends String> values) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return values.stream().map(ResourceLocation::parse).anyMatch(key -> key.equals(id) || stack.is(ItemTags.create(key)));
    }

    private static boolean tool(ItemStack stack) {
        return stack.getMaxDamage() > 0;
    }

    private static void pickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || event.canPickup() == TriState.FALSE
                || !equipped(player, BELT.get())) return;
        var entity = event.getItemEntity();
        ItemStack stack = entity.getItem();
        if (entity.hasPickUpDelay() || entity.getTarget() != null && !player.getUUID().equals(entity.getTarget())
                || stack.isEmpty() || IGNORE_TOOLS.get() && tool(stack) || listed(stack, BELT_NEVER.get())
                || listed(stack, BELT_SKIP.get())) return;
        var inventory = player.getInventory();
        if (FILL_FIRST.get() && inventory.contains(stack)) return;
        var knowledge = knowledge(player);
        var proxy = IEMCProxy.INSTANCE;
        BigInteger value = DecimalEmc.sellValue(stack);
        if (knowledge == null || value.signum() <= 0) return;
        var learned = proxy.getPersistentInfo(ItemInfo.fromStack(stack));
        if (knowledge.addKnowledge(learned)) knowledge.syncKnowledgeChange(player, learned, true);
        setBalance(player, knowledge, DecimalEmc.balance(knowledge).add(value.multiply(BigInteger.valueOf(stack.getCount()))));
        player.take(entity, stack.getCount());
        entity.discard();
        event.setCanPickup(TriState.FALSE);
    }

    private static void refill(PlayerDestroyItemEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() == null
                || !equipped(player, REFILLER.get())) return;
        ItemStack sample = event.getOriginal().copyWithCount(1);
        if (sample.isDamageableItem()) sample.setDamageValue(0);
        IKnowledgeProvider knowledge = knowledge(player);
        var proxy = IEMCProxy.INSTANCE;
        var info = ItemInfo.fromStack(sample);
        BigInteger cost = DecimalEmc.value(info);
        if (knowledge == null || cost.signum() <= 0 || !knowledge.hasKnowledge(proxy.getPersistentInfo(info))) return;
        BigInteger balance = DecimalEmc.balance(knowledge);
        BigInteger affordable = balance.divide(cost).min(BigInteger.valueOf(sample.getMaxStackSize()));
        if (affordable.signum() <= 0) return;
        setBalance(player, knowledge, balance.subtract(cost.multiply(affordable)));
        ItemStack replacement = sample.copyWithCount(affordable.intValue());
        if (player.getItemInHand(event.getHand()).isEmpty()) player.setItemInHand(event.getHand(), replacement);
        else if (!player.addItem(replacement)) player.drop(replacement, false);
    }

    private static final class Wearable extends Item implements ICurioItem {
        private final String hint;
        private final long perSecond;

        Wearable(String hint, long perSecond) {
            super(new Properties().stacksTo(1));
            this.hint = hint;
            this.perSecond = perSecond;
        }

        @Override
        public void curioTick(SlotContext slot, ItemStack stack) {
            if (perSecond == 0 || !(slot.entity() instanceof ServerPlayer player) || player.tickCount % 20 != 0) return;
            var knowledge = knowledge(player);
            if (knowledge != null) setBalance(player, knowledge, DecimalEmc.balance(knowledge).add(DecimalEmc.fromWhole(perSecond)));
        }

        @Override
        public boolean canEquipFromUse(SlotContext slot, ItemStack stack) {
            return true;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
            lines.add(text(hint, perSecond).withStyle(ChatFormatting.GRAY));
        }
    }
}
