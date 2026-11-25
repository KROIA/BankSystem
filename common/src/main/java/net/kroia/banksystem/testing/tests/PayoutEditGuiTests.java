package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.screen.custom.PayoutEditScreen;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;
import net.kroia.testkit.gui.Gui;
import net.kroia.testkit.gui.GuiNode;
import net.kroia.testkit.gui.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * BS-293 on the client: in a new payout, clicking "One-time" unticks "Dividend" and the other way round (a one-time
 * payout pays one target). Needs the ModUtilities GuiLocator. Register on the client only.
 */
public class PayoutEditGuiTests extends TestSuite {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".payout_edit_screen.";

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.GUI;
    }

    @Override
    public Side side() {
        return Side.CLIENT;
    }

    @Override
    public void registerTests() {
        addTest("one_time_and_dividend_exclude_each_other", this::modesExclusive);
    }

    @Override
    public void teardown() {
        if (Gui.screen() instanceof PayoutEditScreen) Gui.close();
    }

    private TestResult modesExclusive() {
        UUID player = Gui.onClient(() -> Minecraft.getInstance().player.getUUID());
        Gui.onClient(() -> net.kroia.modutilities.gui.client.ClientCompat.setScreen(
                new PayoutEditScreen(null, 1, null, player, true, () -> {})));
        Gui.waitForScreen(PayoutEditScreen.class, 20);
        GuiNode dividend = Gui.find(Gui.onClient(() -> Component.translatable(PREFIX + "mode_dividend").getString()));
        GuiNode oneTime = Gui.find(Gui.onClient(() -> Component.translatable(PREFIX + "one_time").getString()));

        clickBox(dividend);
        Gui.waitTicks(2);
        if (!checked(dividend) || checked(oneTime)) return fail("after clicking Dividend: " + state(dividend, oneTime));
        clickBox(oneTime);
        Gui.waitTicks(2);
        if (!checked(oneTime) || checked(dividend)) return fail("after clicking One-time: " + state(dividend, oneTime));
        clickBox(dividend);
        Gui.waitTicks(2);
        if (!checked(dividend) || checked(oneTime)) return fail("after clicking Dividend again: " + state(dividend, oneTime));
        return pass("clicking one of Dividend / One-time unticks the other");
    }

    /**
     * Clicks the small box at the right end of a ModUtilities CheckBox (height minus 2 px padding each side): the
     * box is its only hitbox. ponytail: once MU-182 makes the label clickable too, Gui.click(node) is enough.
     */
    private static void clickBox(GuiNode node) {
        int box = node.height() - 4;
        Gui.click(node.x() + node.width() - 2 - box / 2.0, node.y() + node.height() / 2.0, Keys.MOUSE_LEFT);
    }

    private static boolean checked(GuiNode node) {
        return Gui.onClient(() -> ((CheckBox) node.handle()).isChecked());
    }

    private static String state(GuiNode dividend, GuiNode oneTime) {
        return "dividend=" + checked(dividend) + ", one-time=" + checked(oneTime);
    }
}
