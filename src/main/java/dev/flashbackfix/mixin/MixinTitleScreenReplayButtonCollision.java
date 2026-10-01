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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Flashback's replay-list button at its original slot when late-added icons collide. */
@Mixin(value = TitleScreen.class, priority = 500)
public abstract class MixinTitleScreenReplayButtonCollision extends Screen {

    private static final String OPEN_REPLAYS_KEY = "flashback.open_replays";
    private static final int MAX_SIDE_BUTTON_SIZE = 32;

    @Unique
    private FlashbackButton flashbackNeoForgeFixed$replayButton;

    @Unique
    private int flashbackNeoForgeFixed$replayButtonOriginX;

    @Unique
    private int flashbackNeoForgeFixed$replayButtonOriginY;

    @Unique
    private final Map<AbstractWidget, ButtonPlacement>
            flashbackNeoForgeFixed$relocatedTitleButtons = new IdentityHashMap<>();

    private MixinTitleScreenReplayButtonCollision() {
        super(Component.empty());
    }

    // Flashback creates the button from its TitleScreen.init tail injection. This lower-priority
    // mixin captures that position afterwards, before NeoForge's Init.Post listeners add icons.
    @Inject(method = "init", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$captureReplayButtonOrigin(CallbackInfo ci) {
        flashbackNeoForgeFixed$replayButton = null;
        flashbackNeoForgeFixed$relocatedTitleButtons.clear();
        flashbackNeoForgeFixed$findAndCaptureReplayButton();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$resolveLateTitleButtonCollision(CallbackInfo ci) {
        flashbackNeoForgeFixed$resolveTitleButtonCollision();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$resolveTitleButtonCollisionBeforeRender(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        flashbackNeoForgeFixed$resolveTitleButtonCollision();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$preserveTitleButtonCollisionLayoutAfterRender(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        // Some menu customizers update widget positions while rendering. Re-apply the resolved
        // hitboxes afterwards so the next mouse event uses the same non-overlapping layout.
        flashbackNeoForgeFixed$resolveTitleButtonCollision();
    }

    @Unique
    private void flashbackNeoForgeFixed$findAndCaptureReplayButton() {
        for (Renderable renderable : this.renderables) {
            if (renderable instanceof FlashbackButton button
                    && flashbackNeoForgeFixed$isOpenReplaysButton(button)) {
                flashbackNeoForgeFixed$replayButton = button;
                flashbackNeoForgeFixed$replayButtonOriginX = button.getX();
                flashbackNeoForgeFixed$replayButtonOriginY = button.getY();
                return;
            }
        }
    }

    @Unique
    private void flashbackNeoForgeFixed$resolveTitleButtonCollision() {
        if (flashbackNeoForgeFixed$replayButton == null
                || !this.renderables.contains(flashbackNeoForgeFixed$replayButton)) {
            flashbackNeoForgeFixed$findAndCaptureReplayButton();
        }

        FlashbackButton replayButton = flashbackNeoForgeFixed$replayButton;
        if (replayButton == null || !replayButton.visible) {
            return;
        }

        List<AbstractWidget> sideButtons = new ArrayList<>();

        for (Renderable renderable : this.renderables) {
            if (!(renderable instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }

            ButtonPlacement previous = flashbackNeoForgeFixed$relocatedTitleButtons.get(widget);
            if (previous != null) {
                if (widget.getX() == previous.movedX() && widget.getY() == previous.movedY()) {
                    widget.setPosition(previous.originX(), previous.originY());
                } else {
                    // Respect a later layout update made by the widget's owning mod.
                    flashbackNeoForgeFixed$relocatedTitleButtons.remove(widget);
                }
            }

            if (widget != replayButton
                    && widget.getWidth() <= MAX_SIDE_BUTTON_SIZE
                    && widget.getHeight() <= MAX_SIDE_BUTTON_SIZE) {
                sideButtons.add(widget);
            }
        }

        flashbackNeoForgeFixed$relocatedTitleButtons.clear();
        List<AbstractWidget> collidingAtOrigin = new ArrayList<>();
        List<AbstractWidget> collidingAtCurrentPosition = new ArrayList<>();
        for (AbstractWidget sideButton : sideButtons) {
            if (overlapsAt(
                    replayButton,
                    flashbackNeoForgeFixed$replayButtonOriginX,
                    flashbackNeoForgeFixed$replayButtonOriginY,
                    sideButton)) {
                collidingAtOrigin.add(sideButton);
            }
            if (overlaps(replayButton, sideButton)) {
                collidingAtCurrentPosition.add(sideButton);
            }
        }

        // Depending on injection ordering, Flashback may have already moved the replay button
        // before this mixin first observes it. Always handle a real current-position collision too.
        boolean restoreOriginalReplayPosition = !collidingAtOrigin.isEmpty();
        List<AbstractWidget> collidingSideButtons = restoreOriginalReplayPosition
                ? collidingAtOrigin
                : collidingAtCurrentPosition;
        if (collidingSideButtons.isEmpty()) {
            // A layout mod may intentionally position the replay button. Leave that position alone
            // unless a small late-added control occupies Flashback's original slot.
            return;
        }

        // Flashback normally responds to a late collision by moving its own replay button farther
        // right. Restore its original slot and move only the colliding icon down its existing column.
        if (restoreOriginalReplayPosition) {
            replayButton.setPosition(
                    flashbackNeoForgeFixed$replayButtonOriginX,
                    flashbackNeoForgeFixed$replayButtonOriginY);
        }

        for (AbstractWidget sideButton : collidingSideButtons) {
            int originX = sideButton.getX();
            int originY = sideButton.getY();
            int stepY = sideButton.getHeight() + 4;

            for (int slot = 1; slot <= 12; slot++) {
                int candidateY = originY + slot * stepY;
                if (!fitsOnScreen(sideButton, originX, candidateY)) {
                    break;
                }

                sideButton.setPosition(originX, candidateY);
                if (!overlaps(sideButton, replayButton)
                        && !overlapsAnyExcept(sideButton, sideButtons, sideButton)) {
                    flashbackNeoForgeFixed$relocatedTitleButtons.put(
                            sideButton,
                            new ButtonPlacement(originX, originY, originX, candidateY));
                    break;
                }
            }

            if (!flashbackNeoForgeFixed$relocatedTitleButtons.containsKey(sideButton)) {
                sideButton.setPosition(originX, originY);
            }
        }
    }

    @Unique
    private boolean fitsOnScreen(AbstractWidget widget, int x, int y) {
        return x >= 0 && y >= 0
                && x + widget.getWidth() <= this.width
                && y + widget.getHeight() <= this.height;
    }

    @Unique
    private static boolean flashbackNeoForgeFixed$isOpenReplaysButton(AbstractWidget widget) {
        return widget.getMessage().getContents() instanceof TranslatableContents translatable
                && OPEN_REPLAYS_KEY.equals(translatable.getKey());
    }

    @Unique
    private static boolean overlapsAt(
            AbstractWidget first, int firstX, int firstY, AbstractWidget second) {
        return firstX < second.getRight()
                && firstX + first.getWidth() > second.getX()
                && firstY < second.getBottom()
                && firstY + first.getHeight() > second.getY();
    }

    @Unique
    private static boolean overlapsAnyExcept(
            AbstractWidget subject, List<? extends AbstractWidget> others, AbstractWidget excluded) {
        for (AbstractWidget other : others) {
            if (other != excluded && overlaps(subject, other)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean overlaps(AbstractWidget first, AbstractWidget second) {
        return first.getX() < second.getRight()
                && first.getRight() > second.getX()
                && first.getY() < second.getBottom()
                && first.getBottom() > second.getY();
    }
}
