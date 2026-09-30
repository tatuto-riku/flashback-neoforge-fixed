package dev.flashbackfix.mixin;

import com.moulberry.flashback.screen.FlashbackButton;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps late-added pause-menu icons from covering Flashback's recording controls. */
@Mixin(PauseScreen.class)
public abstract class MixinPauseScreenButtonCollision extends Screen {

    private static final String TWEAKED_CONTROLLERS_BUTTON =
            "com.getitemfromblock.create_tweaked_controllers.gui.ModMainConfigButton";

    private MixinPauseScreenButtonCollision() {
        super(null);
    }

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
        List<AbstractWidget> widgets = new ArrayList<>();
        List<AbstractWidget> lateButtons = new ArrayList<>();

        for (Renderable renderable : this.renderables) {
            if (!(renderable instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            widgets.add(widget);
            if (widget instanceof FlashbackButton button) {
                recordingControls.add(button);
            } else if (TWEAKED_CONTROLLERS_BUTTON.equals(widget.getClass().getName())) {
                lateButtons.add(widget);
            }
        }

        if (recordingControls.isEmpty()) {
            return;
        }

        for (AbstractWidget lateButton : lateButtons) {
            if (!overlapsAny(lateButton, recordingControls)) {
                continue;
            }

            int originX = lateButton.getX();
            int originY = lateButton.getY();
            int stepX = lateButton.getWidth() + 4;
            int stepY = lateButton.getHeight() + 4;
            int outward = originX + lateButton.getWidth() / 2 < this.width / 2 ? -1 : 1;

            // The Tweaked Controllers icon is attached to the side of a menu row. Prefer moving it
            // one slot farther out, then nearby vertical slots, while keeping every widget usable.
            for (int radius = 1; radius <= 8; radius++) {
                int[][] offsets = {
                        {outward * radius, 0},
                        {0, radius}, {0, -radius},
                        {outward * radius, radius}, {outward * radius, -radius},
                        {-outward * radius, 0}
                };
                for (int[] offset : offsets) {
                    int candidateX = originX + offset[0] * stepX;
                    int candidateY = originY + offset[1] * stepY;
                    if (!fitsOnScreen(lateButton, candidateX, candidateY)) {
                        continue;
                    }
                    lateButton.setPosition(candidateX, candidateY);
                    if (!overlapsAnyExcept(lateButton, widgets, lateButton)) {
                        break;
                    }
                }
                if (!overlapsAnyExcept(lateButton, widgets, lateButton)) {
                    break;
                }
            }

            if (overlapsAnyExcept(lateButton, widgets, lateButton)) {
                lateButton.setPosition(originX, originY);
            }
        }
    }

    private boolean fitsOnScreen(AbstractWidget widget, int x, int y) {
        return x >= 0 && y >= 0
                && x + widget.getWidth() <= this.width
                && y + widget.getHeight() <= this.height;
    }

    private static boolean overlapsAny(AbstractWidget subject, List<? extends AbstractWidget> others) {
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
