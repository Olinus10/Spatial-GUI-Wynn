package org.tastytrash.spatialGUI.mixin.accessor;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the HUD title/subtitle. Servers like Wynncraft draw full-screen
 * backgrounds (e.g. the black lootrun backdrop) as a resource-pack glyph sent as a title.
 */
@Mixin(Gui.class)
public interface GuiTitleAccessor {
    @Accessor("title")
    Component spatialGUI$getTitle();

    @Accessor("subtitle")
    Component spatialGUI$getSubtitle();

    @Accessor("titleTime")
    int spatialGUI$getTitleTime();
}
