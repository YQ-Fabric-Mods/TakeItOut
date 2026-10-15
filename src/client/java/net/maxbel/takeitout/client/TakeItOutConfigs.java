package net.maxbel.takeitout.client;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;

import java.util.ArrayList;
import java.util.List;

public final class TakeItOutConfigs {
    public static final ConfigColor CONTAINER_SOURCE_OUTLINE_COLOR = new ConfigColor(
            "containerSourceOutlineColor",
            "#FF22C55E"
    );

    public static final ConfigBoolean BOX_SELECT_CREATES_NEW_GROUP = new ConfigBoolean(
            "boxSelectCreatesNewGroup",
            false
    );

    public static final ConfigBoolean IGNORE_INVENTORY_CONTAINERS = new ConfigBoolean(
            "ignoreInventoryContainers",
            false
    );

    public static final List<IConfigBase> GENERIC_LIST = List.of(
            CONTAINER_SOURCE_OUTLINE_COLOR,
            IGNORE_INVENTORY_CONTAINERS
    );

    public static final List<IConfigBase> SETTINGS_LIST;

    static {
        CONTAINER_SOURCE_OUTLINE_COLOR.setPrettyName("Container Source Outline Color");
        CONTAINER_SOURCE_OUTLINE_COLOR.setTranslatedName("Container Source Outline Color");
        CONTAINER_SOURCE_OUTLINE_COLOR.setComment("Color of the outline rendered around linked world containers.");

        BOX_SELECT_CREATES_NEW_GROUP.setPrettyName("Box Select Creates New Group");
        BOX_SELECT_CREATES_NEW_GROUP.setTranslatedName("Box Select Creates New Group");
        BOX_SELECT_CREATES_NEW_GROUP.setComment("When enabled, box-selecting a region creates a new group instead of adding to the current one.");

        IGNORE_INVENTORY_CONTAINERS.setPrettyName("takeitout.config.name.ignoreInventoryContainers");
        IGNORE_INVENTORY_CONTAINERS.setTranslatedName("takeitout.config.name.ignoreInventoryContainers");
        IGNORE_INVENTORY_CONTAINERS.setComment("Skip carried container contents when taking items and continue searching linked world containers.");

        List<IConfigBase> settings = new ArrayList<>();
        settings.addAll(GENERIC_LIST);
        settings.add(BOX_SELECT_CREATES_NEW_GROUP);
        settings.addAll(TakeItOutHotkeys.HOTKEY_LIST);
        SETTINGS_LIST = List.copyOf(settings);
    }

    private TakeItOutConfigs() {
    }

    public static void initCallbacks() {
        CONTAINER_SOURCE_OUTLINE_COLOR.setValueChangeCallback(config ->
                TakeitoutClient.setContainerSourceOutlineColor(config.getIntegerValue())
        );
    }
}
