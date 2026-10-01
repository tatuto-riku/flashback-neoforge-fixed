package dev.flashbackfix.mixin;

import com.moulberry.flashback.screen.FlashbackButton;
import dev.flashbackfix.compat.ButtonPlacement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps small mod-added pause-menu controls from covering Flashback's recording controls. */
@Mixin(PauseScreen.class)
public abstract class MixinPauseScreenButtonCollision extends Screen {

    private static final int MAX_SIDE_BUTTON_SIZE = 32;

    @Unique
    private final Map<AbstractWidget, ButtonPlacement>
            flashbackNeoForgeFixed$relocatedButtons = new IdentityHashMap<>();

    private MixinPauseScreenButtonCollision() {
        super(Component.empty());
    }

    // NeoForge screen events can add their widgets after PauseScreen.init has returned. Resolve at
    // render/tick time so the result is independent of mod event ordering, then leave the widget at
    // its stable non-overlapping position on following frames.
    @Inject(method = "tick", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$avoidLatePauseButtonCollisions(CallbackInfo ci) {
        flashbackNeoForgeFixed$resolvePauseButtonCollisions();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$avoidPauseButtonCollisionsBeforeRender(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        flashbackNeoForgeFixed$resolvePauseButtonCollisions();
    }

    private void flashbackNeoForgeFixed$resolvePauseButtonCollisions() {
        List<FlashbackButton> recordingControls = new ArrayList<>();
        List<AbstractWidget> sideButtons = new ArrayList<>();

        for (Renderable renderable : this.renderables) {
            if (!(renderable instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            if (widget instanceof FlashbackButton button) {
                recordingControls.add(button);
            } else if (widget.getWidth() <= MAX_SIDE_BUTTON_SIZE
                    && widget.getHeight() <= MAX_SIDE_BUTTON_SIZE) {
                ButtonPlacement previous = flashbackNeoForgeFixed$relocatedButtons.get(widget);
                if (previous != null) {
                    if (widget.getX() == previous.movedX() && widget.getY() == previous.movedY()) {
                        // Re-evaluate every frame from the position chosen by the owning mod. This
                        // also restores the normal layout as soon as the collision disappears.
                        widget.setPosition(previous.originX(), previous.originY());
                    } else {
                        // Another mod deliberately moved the widget after us. Treat that new
                        // position as authoritative instead of fighting its layout updates.
                        flashbackNeoForgeFixed$relocatedButtons.remove(widget);
                    }
                }
                sideButtons.add(widget);
            }
        }

        if (recordingControls.isEmpty()) {
            flashbackNeoForgeFixed$relocatedButtons.clear();
            return;
        }

        for (AbstractWidget sideButton : sideButtons) {
            if (!overlapsAny(sideButton, recordingControls)) {
                continue;
            }

            int originX = sideButton.getX();
            int originY = sideButton.getY();
            int stepX = sideButton.getWidth() + 4;
            boolean placed = false;

            // Side icons are intentionally allowed to overlap the edge of their wide anchor row.
            // Treating that row as an obstacle makes every vertical slot appear occupied on narrow
            // GUIs. Keep the owning mod's Y coordinate and move only to a free slot on its right.
            for (int slot = 1; slot <= 12; slot++) {
                int candidateX = originX + slot * stepX;
                if (!fitsOnScreen(sideButton, candidateX, originY)) {
                    break;
                }
                sideButton.setPosition(candidateX, originY);
                if (!overlapsAny(sideButton, recordingControls)
                        && !overlapsAnyExcept(sideButton, sideButtons, sideButton)) {
                    flashbackNeoForgeFixed$relocatedButtons.put(
                            sideButton,
                            new ButtonPlacement(originX, originY, candidateX, originY));
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                sideButton.setPosition(originX, originY);
            }
        }
    }

    private boolean fitsOnScreen(AbstractWidget widget, int x, int y) {
        return x >= 0 && y >= 0
                && x + widget.getWidth() <= this.width
                && y + widget.getHeight() <= this.height;
    }

    private static boolean overlapsAny(
            AbstractWidget subject, List<? extends AbstractWidget> others) {
        for (AbstractWidget other : others) {
            if (overlaps(subject, other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsAnyExcept(
            AbstractWidget subject, List<? extends AbstractWidget> others, AbstractWidget excluded) {
        for (AbstractWidget other : others) {
            if (other != excluded && overlaps(subject, other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(AbstractWidget first, AbstractWidget second) {
        return first.getX() < second.getRight()
                && first.getRight() > second.getX()
                && first.getY() < second.getBottom()
                && first.getBottom() > second.getY();
    }
}
