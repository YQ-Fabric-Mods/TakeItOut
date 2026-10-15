package net.maxbel.takeitout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Takeitout implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/server");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SERVER_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-server.json");
    private static final Path SHARED_GROUPS_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-shared-groups.json");
    private static final int MAX_GROUPS_PER_PLAYER = 10;
    private static final String LINKED_CONTAINER_EXCHANGE_MODE_KEY = "linked_container_exchange_mode";
    private static final String ALLOWED_EXCHANGE_DIMENSIONS_KEY = "allowed_exchange_dimensions";
    private static final String LINKED_CONTAINER_SCAN_LIMIT_KEY = "linked_container_scan_limit";
    private static final String ALLOW_ALL_ITEMS_TAKE_KEY = "allow_all_items_take";
    private static final int DEFAULT_LINKED_CONTAINER_SCAN_LIMIT = 64;
    private static final Set<String> ALLOWED_EXCHANGE_DIMENSIONS = new HashSet<>();
    private static LinkedContainerExchangeMode linkedContainerExchangeMode = LinkedContainerExchangeMode.CROSS_DIMENSION;
    private static int linkedContainerScanLimit = DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
    private static boolean allowAllItemsTake = true;
    private static final Map<UUID, ContainerReturnTracker<ReturnSource>> RETURN_TRACKERS = new HashMap<>();

    private sealed interface ReturnSource permits WorldReturnSource, ShulkerReturnSource {}
    private record WorldReturnSource(WorldContainerSource source) implements ReturnSource {}
    private record ShulkerReturnSource(String id) implements ReturnSource {}

    public record GetShulkerStackPayload(int slot, int shulker, boolean singleItemMode) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetShulkerStackPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "getstack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetShulkerStackPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT,
                        GetShulkerStackPayload::slot,
                        ByteBufCodecs.INT,
                        GetShulkerStackPayload::shulker,
                        ByteBufCodecs.BOOL,
                        GetShulkerStackPayload::singleItemMode,
                        GetShulkerStackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerSource(String dimension, long position) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerSource> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        WorldContainerSource::dimension,
                        ByteBufCodecs.LONG,
                        WorldContainerSource::position,
                        WorldContainerSource::new
                );
    }

    private enum LinkedContainerExchangeMode {
        DISABLED("disabled"),
        SAME_DIMENSION("same_dimension"),
        CROSS_DIMENSION("cross_dimension");

        private final String id;

        LinkedContainerExchangeMode(String id) {
            this.id = id;
        }

        private static LinkedContainerExchangeMode fromString(String value) {
            if (value != null) {
                for (LinkedContainerExchangeMode mode : values()) {
                    if (mode.id.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                        return mode;
                    }
                }
            }

            LOGGER.warn("Invalid linked container exchange mode '{}', using {}", value, CROSS_DIMENSION.id);
            return CROSS_DIMENSION;
        }
    }

    public record GetWorldContainerStackPayload(List<WorldContainerSource> sources, ItemStack stack, boolean singleItemMode, boolean fromUi, List<WorldContainerSource> dumps)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetWorldContainerStackPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "get_world_container_stack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetWorldContainerStackPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerStackPayload::sources,
                        ItemStack.STREAM_CODEC,
                        GetWorldContainerStackPayload::stack,
                        ByteBufCodecs.BOOL,
                        GetWorldContainerStackPayload::singleItemMode,
                        ByteBufCodecs.BOOL,
                        GetWorldContainerStackPayload::fromUi,
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerStackPayload::dumps,
                        GetWorldContainerStackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerStackResponsePayload(ItemStack stack, boolean success) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldContainerStackResponsePayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "world_container_stack_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerStackResponsePayload> CODEC =
                StreamCodec.composite(
                        ItemStack.STREAM_CODEC,
                        WorldContainerStackResponsePayload::stack,
                        ByteBufCodecs.BOOL,
                        WorldContainerStackResponsePayload::success,
                        WorldContainerStackResponsePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerItemCount(ItemStack stack, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerItemCount> CODEC =
                StreamCodec.composite(
                        ItemStack.STREAM_CODEC,
                        WorldContainerItemCount::stack,
                        ByteBufCodecs.INT,
                        WorldContainerItemCount::count,
                        WorldContainerItemCount::new
                );
    }

    public record WorldContainerContents(WorldContainerSource source, List<WorldContainerItemCount> items) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerContents> CODEC =
                StreamCodec.composite(
                        WorldContainerSource.CODEC,
                        WorldContainerContents::source,
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerItemCount.CODEC),
                        WorldContainerContents::items,
                        WorldContainerContents::new
                );
    }

    public record GetWorldContainerItemsPayload(List<WorldContainerSource> sources) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetWorldContainerItemsPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "get_world_container_items"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetWorldContainerItemsPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerItemsPayload::sources,
                        GetWorldContainerItemsPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerItemsPayload(List<WorldContainerItemCount> items, List<WorldContainerContents> containers)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldContainerItemsPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "world_container_items"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerItemsPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerItemCount.CODEC),
                        WorldContainerItemsPayload::items,
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerContents.CODEC),
                        WorldContainerItemsPayload::containers,
                        WorldContainerItemsPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record DumpInventoryPayload(List<WorldContainerSource> dumps) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DumpInventoryPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "dump_inventory"));

        public static final StreamCodec<RegistryFriendlyByteBuf, DumpInventoryPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        DumpInventoryPayload::dumps,
                        DumpInventoryPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record ServerConfigSyncPayload(int linkedContainerScanLimit) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ServerConfigSyncPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "server_config_sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSyncPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        ServerConfigSyncPayload::linkedContainerScanLimit,
                        ServerConfigSyncPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record SharedSourceEntry(long position, boolean linked) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SharedSourceEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.LONG,
                        SharedSourceEntry::position,
                        ByteBufCodecs.BOOL,
                        SharedSourceEntry::linked,
                        SharedSourceEntry::new
                );
    }

    public record SharedGroupDimension(String dimension, List<SharedSourceEntry> sources) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SharedGroupDimension> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupDimension::dimension,
                        ByteBufCodecs.collection(ArrayList::new, SharedSourceEntry.CODEC),
                        SharedGroupDimension::sources,
                        SharedGroupDimension::new
                );
    }

    public record SharedGroupEntry(String id, String name, String authorName, String authorId, List<SharedGroupDimension> dimensions) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SharedGroupEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::id,
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::name,
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::authorName,
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::authorId,
                        ByteBufCodecs.collection(ArrayList::new, SharedGroupDimension.CODEC),
                        SharedGroupEntry::dimensions,
                        SharedGroupEntry::new
                );
    }

    public record PublishGroupPayload(String name, List<SharedGroupDimension> dimensions) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PublishGroupPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "publish_group"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PublishGroupPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        PublishGroupPayload::name,
                        ByteBufCodecs.collection(ArrayList::new, SharedGroupDimension.CODEC),
                        PublishGroupPayload::dimensions,
                        PublishGroupPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record UnpublishGroupPayload(String groupId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UnpublishGroupPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "unpublish_group"));

        public static final StreamCodec<RegistryFriendlyByteBuf, UnpublishGroupPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        UnpublishGroupPayload::groupId,
                        UnpublishGroupPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record SharedGroupsListPayload(List<SharedGroupEntry> groups) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SharedGroupsListPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "shared_groups_list"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SharedGroupsListPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, SharedGroupEntry.CODEC),
                        SharedGroupsListPayload::groups,
                        SharedGroupsListPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    @Override
    public void onInitialize() {
        loadServerConfig();

        PayloadTypeRegistry.serverboundPlay().register(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GetWorldContainerStackPayload.ID, GetWorldContainerStackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GetWorldContainerItemsPayload.ID, GetWorldContainerItemsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DumpInventoryPayload.ID, DumpInventoryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PublishGroupPayload.ID, PublishGroupPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UnpublishGroupPayload.ID, UnpublishGroupPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldContainerStackResponsePayload.ID, WorldContainerStackResponsePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldContainerItemsPayload.ID, WorldContainerItemsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ServerConfigSyncPayload.ID, ServerConfigSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SharedGroupsListPayload.ID, SharedGroupsListPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GetShulkerStackPayload.ID, (payload, context) ->
                context.server().execute(() -> handleGetShulkerStackPayload(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(GetWorldContainerStackPayload.ID, (payload, context) ->
                context.server().execute(() -> handleGetWorldContainerStackPayload(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(GetWorldContainerItemsPayload.ID, (payload, context) ->
                context.server().execute(() -> handleGetWorldContainerItemsPayload(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(DumpInventoryPayload.ID, (payload, context) ->
                context.server().execute(() -> handleDumpInventoryPayload(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(PublishGroupPayload.ID, (payload, context) ->
                context.server().execute(() -> handlePublishGroupPayload(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(UnpublishGroupPayload.ID, (payload, context) ->
                context.server().execute(() -> handleUnpublishGroupPayload(context.player(), payload))
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            RETURN_TRACKERS.remove(handler.getPlayer().getUUID());
            sender.sendPacket(new ServerConfigSyncPayload(linkedContainerScanLimit));
            sender.sendPacket(new SharedGroupsListPayload(loadSharedGroups()));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> RETURN_TRACKERS.remove(handler.getPlayer().getUUID())));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RETURN_TRACKERS.clear());
    }

    private static void handlePublishGroupPayload(ServerPlayer player, PublishGroupPayload payload) {
        String name = payload.name();
        if (name == null || name.isBlank() || name.length() > 64) return;
        if (payload.dimensions() == null) return;

        String playerId = player.getGameProfile().id().toString();
        String playerName = player.getGameProfile().name();

        List<SharedGroupEntry> groups = loadSharedGroups();
        groups.removeIf(g -> g.authorId().equals(playerId) && g.name().equals(name));

        long playerGroupCount = groups.stream().filter(g -> g.authorId().equals(playerId)).count();
        if (playerGroupCount >= MAX_GROUPS_PER_PLAYER) {
            player.sendSystemMessage(Component.literal("TakeItOut: shared group limit reached (" + MAX_GROUPS_PER_PLAYER + ")"));
            return;
        }

        String groupId = UUID.randomUUID().toString();
        groups.add(new SharedGroupEntry(groupId, name, playerName, playerId, payload.dimensions()));
        saveSharedGroups(groups);
        broadcastSharedGroups(player.level().getServer(), groups);
        player.sendSystemMessage(Component.literal("TakeItOut: group \"" + name + "\" shared on server"));
    }

    private static void handleUnpublishGroupPayload(ServerPlayer player, UnpublishGroupPayload payload) {
        String groupId = payload.groupId();
        if (groupId == null || groupId.isBlank()) return;

        String playerId = player.getGameProfile().id().toString();
        List<SharedGroupEntry> groups = loadSharedGroups();
        boolean removed = groups.removeIf(g -> g.id().equals(groupId) && g.authorId().equals(playerId));

        if (removed) {
            saveSharedGroups(groups);
            broadcastSharedGroups(player.level().getServer(), groups);
            player.sendSystemMessage(Component.literal("TakeItOut: group removed from server"));
        }
    }

    private static List<SharedGroupEntry> loadSharedGroups() {
        if (!Files.exists(SHARED_GROUPS_PATH)) return new ArrayList<>();
        try {
            JsonArray arr = GSON.fromJson(Files.readString(SHARED_GROUPS_PATH), JsonArray.class);
            if (arr == null) return new ArrayList<>();
            List<SharedGroupEntry> groups = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                try {
                    String id = obj.get("id").getAsString();
                    String name = obj.get("name").getAsString();
                    String authorName = obj.get("authorName").getAsString();
                    String authorId = obj.get("authorId").getAsString();
                    List<SharedGroupDimension> dimensions = new ArrayList<>();
                    if (obj.has("dimensions") && obj.get("dimensions").isJsonArray()) {
                        for (JsonElement dimEl : obj.getAsJsonArray("dimensions")) {
                            if (!dimEl.isJsonObject()) continue;
                            JsonObject dimObj = dimEl.getAsJsonObject();
                            String dimension = dimObj.get("dimension").getAsString();
                            List<SharedSourceEntry> sources = new ArrayList<>();
                            if (dimObj.has("sources") && dimObj.get("sources").isJsonArray()) {
                                for (JsonElement srcEl : dimObj.getAsJsonArray("sources")) {
                                    if (!srcEl.isJsonObject()) continue;
                                    JsonObject srcObj = srcEl.getAsJsonObject();
                                    sources.add(new SharedSourceEntry(srcObj.get("pos").getAsLong(), srcObj.get("linked").getAsBoolean()));
                                }
                            }
                            dimensions.add(new SharedGroupDimension(dimension, sources));
                        }
                    }
                    groups.add(new SharedGroupEntry(id, name, authorName, authorId, dimensions));
                } catch (Exception ignored) {}
            }
            return groups;
        } catch (Exception e) {
            LOGGER.warn("Failed to load shared groups", e);
            return new ArrayList<>();
        }
    }

    private static void saveSharedGroups(List<SharedGroupEntry> groups) {
        try {
            Files.createDirectories(SHARED_GROUPS_PATH.getParent());
            JsonArray arr = new JsonArray();
            for (SharedGroupEntry group : groups) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", group.id());
                obj.addProperty("name", group.name());
                obj.addProperty("authorName", group.authorName());
                obj.addProperty("authorId", group.authorId());
                JsonArray dims = new JsonArray();
                for (SharedGroupDimension dim : group.dimensions()) {
                    JsonObject dimObj = new JsonObject();
                    dimObj.addProperty("dimension", dim.dimension());
                    JsonArray sources = new JsonArray();
                    for (SharedSourceEntry src : dim.sources()) {
                        JsonObject srcObj = new JsonObject();
                        srcObj.addProperty("pos", src.position());
                        srcObj.addProperty("linked", src.linked());
                        sources.add(srcObj);
                    }
                    dimObj.add("sources", sources);
                    dims.add(dimObj);
                }
                obj.add("dimensions", dims);
                arr.add(obj);
            }
            Files.writeString(SHARED_GROUPS_PATH, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.warn("Failed to save shared groups", e);
        }
    }

    private static void broadcastSharedGroups(net.minecraft.server.MinecraftServer server, List<SharedGroupEntry> groups) {
        SharedGroupsListPayload packet = new SharedGroupsListPayload(groups);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(p, packet);
        }
    }

    private static void handleGetShulkerStackPayload(ServerPlayer player, GetShulkerStackPayload payload) {
        int slotInShulker = payload.slot();
        int shulkerSlot = payload.shulker();
        boolean singleItemMode = payload.singleItemMode();

        if (!isValidInventorySlot(player, shulkerSlot)) {
            return;
        }

        ItemStack shulker = player.getInventory().getItem(shulkerSlot);
        if (shulker.isEmpty() || !isShulkerItem(shulker)) {
            return;
        }

        ItemContainerContents contents = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> itemStacks = copyContainerContents(contents);

        if (slotInShulker < 0 || slotInShulker >= itemStacks.size()) {
            return;
        }

        if (shulker.getCount() != 1 || itemStacks.get(slotInShulker).isEmpty()) return;
        ShulkerReturnSource source = new ShulkerReturnSource(CarriedShulkerInventory.identify(shulker));
        CarriedShulkerInventory box = CarriedShulkerInventory.open(shulker);
        InventoryAccess access = new InventoryAccess(box, () -> {});
        extractFromContainer(player, source, access, slotInShulker, singleItemMode, shulkerSlot);
    }

    private static void handleGetWorldContainerStackPayload(ServerPlayer player, GetWorldContainerStackPayload payload) {
        ItemStack requested = payload.stack();
        if (requested == null || requested.isEmpty() || payload.sources() == null) {
            return;
        }

        if (payload.fromUi() && !allowAllItemsTake) {
            player.sendSystemMessage(Component.literal("TakeItOut: taking items via All Items tab is disabled on this server"));
            ServerPlayNetworking.send(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), false));
            return;
        }

        int checked = 0;
        int invalidSourceCount = 0;
        int emptySourceCount = 0;
        int failedExtractCount = 0;
        int scanLimit = linkedContainerScanLimit;
        for (WorldContainerSource source : payload.sources()) {
            if (checked >= scanLimit) {
                break;
            }
            checked++;

            BlockPos pos = BlockPos.of(source.position());
            Container inventory = getWorldContainerInventory(player, source);
            if (inventory == null) {
                invalidSourceCount++;
                continue;
            }

            int slot = getSlotWithStack(inventory, requested);
            if (slot != -1 && extractFromWorldContainer(player, source, inventory, slot, payload.singleItemMode())) {
                ServerPlayNetworking.send(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
                LOGGER.debug(
                        "GetWorldContainerStack success: player={}, requested={}, pos={}, slot={}, singleItemMode={}",
                        player.getName().getString(),
                        requested,
                        pos,
                        slot,
                        payload.singleItemMode()
                );
                return;
            }

            if (slot == -1) {
                emptySourceCount++;
            } else {
                failedExtractCount++;
            }
        }

        // Second pass: item not found directly — find a shulker box containing the most of the requested item
        if (!isShulkerItem(requested)) {
            int bestShulkerSlot = -1;
            int bestShulkerCount = 0;
            Container bestShulkerInventory = null;
            BlockPos bestShulkerPos = null;
            WorldContainerSource bestShulkerSource = null;

            int scanned = 0;
            for (WorldContainerSource source : payload.sources()) {
                if (scanned >= scanLimit) break;
                scanned++;

                Container inventory = getWorldContainerInventory(player, source);
                if (inventory == null) continue;

                BlockPos pos = BlockPos.of(source.position());
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (stack == null || stack.isEmpty() || !isShulkerItem(stack)) continue;

                    ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                    int count = 0;
                    for (ItemStack shulkerItem : copyContainerContents(contents)) {
                        if (!shulkerItem.isEmpty() && shulkerItem.is(requested.getItem())) {
                            count += shulkerItem.getCount();
                        }
                    }

                    if (count > bestShulkerCount) {
                        bestShulkerCount = count;
                        bestShulkerSlot = i;
                        bestShulkerInventory = inventory;
                        bestShulkerPos = pos;
                        bestShulkerSource = source;
                    }
                }
            }

            if (bestShulkerSlot != -1 && extractFromWorldContainer(player, bestShulkerSource, bestShulkerInventory, bestShulkerSlot, true)) {
                ServerPlayNetworking.send(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
                LOGGER.debug(
                        "GetWorldContainerStack shulker fallback success: player={}, requested={}, pos={}, slot={}, itemCount={}",
                        player.getName().getString(),
                        requested,
                        bestShulkerPos,
                        bestShulkerSlot,
                        bestShulkerCount
                );
                return;
            }
        }

        ServerPlayNetworking.send(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), false));
        LOGGER.debug(
                "GetWorldContainerStack miss: player={}, requested={}, sources={}, invalidSources={}, noMatchingStack={}, failedExtract={}",
                player.getName().getString(),
                requested,
                Math.min(payload.sources().size(), scanLimit),
                invalidSourceCount,
                emptySourceCount,
                failedExtractCount
        );
    }

    private static void handleGetWorldContainerItemsPayload(ServerPlayer player, GetWorldContainerItemsPayload payload) {
        List<WorldContainerItemCount> items = new ArrayList<>();
        List<WorldContainerContents> containers = new ArrayList<>();

        if (payload.sources() == null) {
            ServerPlayNetworking.send(player, new WorldContainerItemsPayload(items, containers));
            return;
        }

        int checked = 0;
        int scanLimit = linkedContainerScanLimit;
        for (WorldContainerSource source : payload.sources()) {
            if (checked >= scanLimit) {
                break;
            }
            checked++;

            Container inventory = getWorldContainerInventory(player, source);
            List<WorldContainerItemCount> containerItems = new ArrayList<>();

            if (inventory == null) {
                containers.add(new WorldContainerContents(source, containerItems));
                continue;
            }

            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }

                addItemCount(items, stack);
                addItemCount(containerItems, stack);
            }

            containers.add(new WorldContainerContents(source, containerItems));
        }

        ServerPlayNetworking.send(player, new WorldContainerItemsPayload(items, containers));
    }

    private static void handleDumpInventoryPayload(ServerPlayer player, DumpInventoryPayload payload) {
        // Retain the legacy packet, but never use its arbitrary dump targets.
        int moved = tracker(player).returnAll(player.getInventory(), source -> resolveReturnInventory(player, source));
        syncPlayerInventory(player);
        player.sendSystemMessage(Component.translatable("message.takeitout.returned_to_sources", moved));
    }

    private static void addItemCount(List<WorldContainerItemCount> items, ItemStack stack) {
        ItemStack keyStack = stack.copyWithCount(1);
        boolean shulker = isShulkerItem(stack);

        for (int i = 0; i < items.size(); i++) {
            WorldContainerItemCount existing = items.get(i);
            boolean matches = shulker
                    ? ItemStack.isSameItemSameComponents(existing.stack(), keyStack)
                    : existing.stack().is(keyStack.getItem());
            if (matches) {
                items.set(i, new WorldContainerItemCount(existing.stack(), existing.count() + stack.getCount()));
                return;
            }
        }

        items.add(new WorldContainerItemCount(keyStack, stack.getCount()));
    }

    private static boolean extractFromWorldContainer(
            ServerPlayer player, WorldContainerSource source, Container inventory,
            int slot, boolean singleItemMode
    ) {
        return extractFromContainer(player, new WorldReturnSource(source),
                new InventoryAccess(inventory, () -> syncWorldContainer(player, inventory)),
                slot, singleItemMode, -1);
    }

    /** Both world containers and carried shulkers use this one extraction/return path. */
    private static boolean extractFromContainer(
            ServerPlayer player, ReturnSource source, InventoryAccess inventory,
            int slot, boolean singleItemMode, int sourceShulkerSlot
    ) {
        boolean success = tracker(player).take(player.getInventory(), player.getInventory().getSelectedSlot(),
                source, inventory, slot, singleItemMode, sourceShulkerSlot,
                target -> resolveReturnInventory(player, target));
        syncPlayerInventory(player);
        return success;
    }

    private static ContainerReturnTracker<ReturnSource> tracker(ServerPlayer player) {
        return RETURN_TRACKERS.computeIfAbsent(player.getUUID(), ignored -> new ContainerReturnTracker<>());
    }

    private static InventoryAccess resolveReturnInventory(ServerPlayer player, ReturnSource source) {
        if (source instanceof WorldReturnSource worldSource) {
            ServerLevel world = getSourceWorld(player, worldSource.source());
            if (world == null || !world.hasChunkAt(BlockPos.of(worldSource.source().position()))) return null;
            Container inventory = getWorldContainerInventory(player, worldSource.source());
            return inventory == null ? null
                    : new InventoryAccess(inventory, () -> syncWorldContainer(player, inventory));
        }
        CarriedShulkerInventory box = CarriedShulkerInventory.find(player.getInventory(),
                ((ShulkerReturnSource) source).id());
        return box == null ? null : new InventoryAccess(box, () -> {});
    }

    /** Flush box contents when a return commits, before the held box can move to another slot. */
    private record InventoryAccess(Container delegate, Runnable flush) implements Container {
        @Override public int getContainerSize() { return delegate.getContainerSize(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public ItemStack getItem(int slot) { return delegate.getItem(slot); }
        @Override public ItemStack removeItem(int slot, int amount) { return delegate.removeItem(slot, amount); }
        @Override public ItemStack removeItemNoUpdate(int slot) { return delegate.removeItemNoUpdate(slot); }
        @Override public void setItem(int slot, ItemStack stack) { delegate.setItem(slot, stack); }
        @Override public void setChanged() { delegate.setChanged(); flush.run(); }
        @Override public boolean stillValid(Player player) { return delegate.stillValid(player); }
        @Override public void clearContent() { delegate.clearContent(); }
        @Override public int getMaxStackSize() { return delegate.getMaxStackSize(); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return delegate.canPlaceItem(slot, stack); }
    }

    private static int getSlotWithStack(Container inventory, ItemStack stackReference) {
        boolean shulker = isShulkerItem(stackReference);
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.isEmpty()) {
                boolean matches = shulker
                        ? ItemStack.isSameItemSameComponents(stack, stackReference)
                        : stack.is(stackReference.getItem());
                if (matches) return i;
            }
        }
        return -1;
    }

    private static Container getWorldContainerInventory(ServerPlayer player, WorldContainerSource source) {
        ServerLevel world = getSourceWorld(player, source);
        if (world == null) {
            return null;
        }

        BlockPos pos = BlockPos.of(source.position());
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Container inventory = null;

        if (block instanceof ChestBlock chestBlock) {
            inventory = ChestBlock.getContainer(chestBlock, state, world, pos, true);
        } else if (block instanceof ShulkerBoxBlock || block instanceof BarrelBlock) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                inventory = container;
            }
        }

        return inventory;
    }

    private static ServerLevel getSourceWorld(ServerPlayer player, WorldContainerSource source) {
        if (source == null || source.dimension() == null || source.dimension().isBlank()) {
            return null;
        }

        String playerDimension = player.level().dimension().identifier().toString();
        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.DISABLED) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, reason=disabled, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.of(source.position())
            );
            return null;
        }

        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.SAME_DIMENSION && !playerDimension.equals(source.dimension())) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, reason=same_dimension_only, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.of(source.position())
            );
            return null;
        }

        if (!isDimensionAllowedForExchange(playerDimension) || !isDimensionAllowedForExchange(source.dimension())) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.of(source.position())
            );
            return null;
        }

        Identifier dimensionId;
        try {
            dimensionId = Identifier.parse(source.dimension());
        } catch (Exception ignored) {
            return null;
        }

        ResourceKey<net.minecraft.world.level.Level> worldKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        return player.level().getServer().getLevel(worldKey);
    }

    private static boolean isDimensionAllowedForExchange(String dimension) {
        return ALLOWED_EXCHANGE_DIMENSIONS.isEmpty() || ALLOWED_EXCHANGE_DIMENSIONS.contains(dimension);
    }

    private static void loadServerConfig() {
        ALLOWED_EXCHANGE_DIMENSIONS.clear();
        linkedContainerExchangeMode = LinkedContainerExchangeMode.CROSS_DIMENSION;
        linkedContainerScanLimit = DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        allowAllItemsTake = true;

        if (!Files.exists(SERVER_CONFIG_PATH)) {
            saveDefaultServerConfig();
            LOGGER.info("Server config created: {}", SERVER_CONFIG_PATH);
            return;
        }

        try {
            JsonObject root = GSON.fromJson(Files.readString(SERVER_CONFIG_PATH), JsonObject.class);
            if (root == null || !root.has(ALLOWED_EXCHANGE_DIMENSIONS_KEY) || !root.get(ALLOWED_EXCHANGE_DIMENSIONS_KEY).isJsonArray()) {
                saveDefaultServerConfig();
                return;
            }

            if (root.has(LINKED_CONTAINER_EXCHANGE_MODE_KEY)) {
                linkedContainerExchangeMode = LinkedContainerExchangeMode.fromString(root.get(LINKED_CONTAINER_EXCHANGE_MODE_KEY).getAsString());
            }

            if (root.has(LINKED_CONTAINER_SCAN_LIMIT_KEY)) {
                linkedContainerScanLimit = parseLinkedContainerScanLimit(root.get(LINKED_CONTAINER_SCAN_LIMIT_KEY));
            } else {
                root.addProperty(LINKED_CONTAINER_SCAN_LIMIT_KEY, linkedContainerScanLimit);
                saveServerConfig(root);
            }

            if (root.has(ALLOW_ALL_ITEMS_TAKE_KEY)) {
                allowAllItemsTake = root.get(ALLOW_ALL_ITEMS_TAKE_KEY).getAsBoolean();
            } else {
                root.addProperty(ALLOW_ALL_ITEMS_TAKE_KEY, allowAllItemsTake);
                saveServerConfig(root);
            }

            JsonArray allowedDimensions = root.getAsJsonArray(ALLOWED_EXCHANGE_DIMENSIONS_KEY);
            for (JsonElement element : allowedDimensions) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }

                String dimension = element.getAsString();
                if (dimension == null || dimension.isBlank()) {
                    continue;
                }

                try {
                    Identifier.parse(dimension);
                    ALLOWED_EXCHANGE_DIMENSIONS.add(dimension);
                } catch (Exception e) {
                    LOGGER.warn("Ignoring invalid TakeItOut exchange dimension in server config: {}", dimension);
                }
            }

            LOGGER.info(
                    "Server config loaded: linkedContainerExchangeMode={}, linkedContainerScanLimit={}, allowAllItemsTake={}, allowedExchangeDimensions={}",
                    linkedContainerExchangeMode.id,
                    linkedContainerScanLimit,
                    allowAllItemsTake,
                    ALLOWED_EXCHANGE_DIMENSIONS.isEmpty() ? "all" : ALLOWED_EXCHANGE_DIMENSIONS
            );
        } catch (Exception e) {
            LOGGER.warn("Failed to load TakeItOut server config, using defaults", e);
        }
    }

    private static int parseLinkedContainerScanLimit(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            LOGGER.warn(
                    "Invalid TakeItOut linked container scan limit '{}', using {}",
                    element,
                    DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        int limit;
        try {
            limit = element.getAsInt();
        } catch (Exception e) {
            LOGGER.warn(
                    "Invalid TakeItOut linked container scan limit '{}', using {}",
                    element,
                    DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        if (limit < 1) {
            LOGGER.warn(
                    "Invalid TakeItOut linked container scan limit '{}', using {}",
                    limit,
                    DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        return limit;
    }

    private static void saveDefaultServerConfig() {
        JsonObject root = new JsonObject();
        root.addProperty(LINKED_CONTAINER_EXCHANGE_MODE_KEY, linkedContainerExchangeMode.id);
        root.addProperty(LINKED_CONTAINER_SCAN_LIMIT_KEY, linkedContainerScanLimit);
        root.addProperty(ALLOW_ALL_ITEMS_TAKE_KEY, allowAllItemsTake);
        root.add(ALLOWED_EXCHANGE_DIMENSIONS_KEY, new JsonArray());

        saveServerConfig(root);
    }

    private static void saveServerConfig(JsonObject root) {
        try {
            Files.createDirectories(SERVER_CONFIG_PATH.getParent());
            Files.writeString(SERVER_CONFIG_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to write TakeItOut server config", e);
        }
    }

    private static boolean isValidInventorySlot(ServerPlayer player, int slot) {
        return slot >= 0 && slot < player.getInventory().getContainerSize();
    }

    private static List<ItemStack> copyContainerContents(ItemContainerContents contents) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(stacks);
        return stacks;
    }

    private static boolean isShulkerItem(ItemStack item) {
        return !item.isEmpty()
                && item.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static void syncPlayerInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static void syncWorldContainer(ServerPlayer player, Container inventory) {
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }
}
