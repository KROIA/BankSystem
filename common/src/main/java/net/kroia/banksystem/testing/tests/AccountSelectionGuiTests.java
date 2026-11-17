package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.screen.custom.BankAccountSelectionScreen;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;
import net.kroia.testkit.gui.Gui;
import net.kroia.testkit.gui.GuiNode;
import net.kroia.testkit.gui.Layout;
import net.kroia.testkit.gui.Screenshots;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TK-3/TK-4: the bank account selection screen driven on the client. Lays out cleanly at a small and a large window,
 * a click on an account row selects that account and closes the screen, and a documentation screenshot is written.
 * Needs the ModUtilities GuiLocator (the screen is built from MU GuiElements). Register on the client only.
 */
public class AccountSelectionGuiTests extends TestSuite {

    /** Ticks the account list gets to arrive from the (master) server. */
    private static final int LIST_TICKS = 200;

    private final AtomicInteger selected = new AtomicInteger(-1);

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
        addTest("layout_small_and_large_window", this::layout);
        addTest("screenshot", this::screenshot);
        addTest("click_account_selects_and_closes", this::clickAccount);
    }

    @Override
    public void teardown() {
        if (Gui.screen() instanceof BankAccountSelectionScreen) Gui.close();
    }

    /** Opens the screen for the joined player and waits until its account list has rows. */
    private List<GuiNode> openWithAccounts() {
        selected.set(-1);
        UUID player = Gui.onClient(() -> Minecraft.getInstance().player.getUUID());
        Gui.onClient(() -> Minecraft.getInstance().setScreen(new BankAccountSelectionScreen(null, player, selected::set)));
        Gui.waitForScreen(BankAccountSelectionScreen.class, 20);
        // polled from the test thread: findAll hops to the client itself, so it cannot run inside Gui.waitUntil
        for (int i = 0; i < LIST_TICKS && accountRows().isEmpty(); i++) Gui.waitTicks(1);
        return accountRows();
    }

    private static List<GuiNode> accountRows() {
        return Gui.findAll(n -> n.handle() instanceof BankAccountSelectionScreen.AccountButton);
    }

    private TestResult layout() {
        if (openWithAccounts().isEmpty()) return fail("no account rows within " + LIST_TICKS + " ticks");
        List<Layout.Problem> small = Gui.layoutProblems(854, 480, 2);
        List<Layout.Problem> large = Gui.layoutProblems(1920, 1080, 3);
        if (!small.isEmpty() || !large.isEmpty())
            return fail("854x480@2: " + small + " | 1920x1080@3: " + large);
        return pass("no layout problems at 854x480 scale 2 and 1920x1080 scale 3");
    }

    private TestResult screenshot() {
        if (openWithAccounts().isEmpty()) return fail("no account rows within " + LIST_TICKS + " ticks");
        Path file = Screenshots.take("banksystem/bank_account_selection.png");
        return assertTrue("screenshot written: " + file, Files.isRegularFile(file));
    }

    private TestResult clickAccount() {
        List<GuiNode> rows = openWithAccounts();
        if (rows.isEmpty()) return fail("no account rows within " + LIST_TICKS + " ticks");
        GuiNode row = rows.get(0);
        int expected = Gui.onClient(() -> ((BankAccountSelectionScreen.AccountButton) row.handle()).getAccountData().accountNumber);
        Gui.click(row);
        if (!Gui.waitUntil(() -> !(Minecraft.getInstance().screen instanceof BankAccountSelectionScreen), 20))
            return fail("screen still open after clicking " + row);
        return assertEquals(expected, selected.get());
    }
}
