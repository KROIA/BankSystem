package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.screen.custom.BankAccountSelectionScreen;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;
import net.kroia.testkit.autotest.AutoTest;
import net.kroia.testkit.autotest.ServerRole;
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
 * TK-3/TK-4: the bank account selection screen driven on the client. Lays out cleanly at Full HD (GUI scale 2 and 3),
 * a click on a shown account row selects that account and closes the screen, and a documentation screenshot is
 * written. Skips on an untrusted slave only: the master refuses its reads, so the list is empty there by design.
 * On a trusted slave the list comes from the master; TestKit starts client GUI suites there only after the master's
 * server suites (TK-9), so the master is free to answer. Also skips in the slave-masterdown cell
 * ({@link AutoTest#isMasterDown()}), where the master is killed on purpose; a link that breaks by accident in any other
 * slave cell still errors.
 * Needs the ModUtilities GuiLocator (the screen is built from MU GuiElements). Register on the client only.
 */
public class AccountSelectionGuiTests extends TestSuite {

    /** Ticks the account list gets to arrive from the (master) server. */
    private static final int LIST_TICKS = 200;
    private static final String UNTRUSTED = "untrusted slave: the master refuses its account reads, empty list expected";
    private static final String MASTER_DOWN = "slave-masterdown cell: the master is down on purpose, no account list";
    private static final String NO_ROWS = "no shown account rows within " + LIST_TICKS + " ticks";

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
        addTest("layout_full_hd_scale_2_and_3", this::layout);
        addTest("screenshot", this::screenshot);
        addTest("click_account_selects_and_closes", this::clickAccount);
    }

    @Override
    public void teardown() {
        if (Gui.screen() instanceof BankAccountSelectionScreen) Gui.close();
    }

    /**
     * True on a slave the cell does not trust (BS-306): TestKit's server role, from run_matrix's serverRole/trustedSlave
     * properties or the local role provider. No round trip to the master, which timed out under load.
     */
    private static boolean onUntrustedSlave() {
        return AutoTest.serverRole() == ServerRole.SLAVE_UNTRUSTED;
    }

    /** Opens the screen for the joined player and waits until its account list has shown rows. */
    private List<GuiNode> openWithAccounts() {
        selected.set(-1);
        UUID player = Gui.onClient(() -> Minecraft.getInstance().player.getUUID());
        Gui.onClient(() -> net.kroia.modutilities.gui.client.ClientCompat.setScreen(new BankAccountSelectionScreen(null, player, selected::set)));
        Gui.waitForScreen(BankAccountSelectionScreen.class, 20);
        // polled from the test thread: findAll hops to the client itself, so it cannot run inside Gui.waitUntil
        for (int i = 0; i < LIST_TICKS && accountRows().isEmpty(); i++) Gui.waitTicks(1);
        return accountRows();
    }

    /** Shown account rows only: a row scrolled out of view is not clickable. */
    private static List<GuiNode> accountRows() {
        return Gui.findAll(n -> n.handle() instanceof BankAccountSelectionScreen.AccountButton && n.visible());
    }

    /** No element outside the screen, no overlapping siblings, no clipped text at 1920x1080, GUI scale 2 and 3. */
    private TestResult layout() {
        if (AutoTest.isMasterDown()) return skip(MASTER_DOWN);
        if (onUntrustedSlave()) return skip(UNTRUSTED);
        if (openWithAccounts().isEmpty()) return fail(NO_ROWS);
        List<Layout.Problem> scale2 = Gui.layoutProblems(1920, 1080, 2);
        List<Layout.Problem> scale3 = Gui.layoutProblems(1920, 1080, 3);
        if (!scale2.isEmpty() || !scale3.isEmpty())
            return fail("1920x1080@2: " + scale2 + " | 1920x1080@3: " + scale3);
        return pass("no layout problems at 1920x1080 scale 2 and 3");
    }

    private TestResult screenshot() {
        if (AutoTest.isMasterDown()) return skip(MASTER_DOWN);
        if (onUntrustedSlave()) return skip(UNTRUSTED);
        if (openWithAccounts().isEmpty()) return fail(NO_ROWS);
        Path file = Screenshots.take("banksystem/bank_account_selection.png");
        return assertTrue("screenshot written: " + file, Files.isRegularFile(file));
    }

    private TestResult clickAccount() {
        if (AutoTest.isMasterDown()) return skip(MASTER_DOWN);
        if (onUntrustedSlave()) return skip(UNTRUSTED);
        List<GuiNode> rows = openWithAccounts();
        if (rows.isEmpty()) return fail(NO_ROWS);
        GuiNode row = rows.get(0);
        int expected = Gui.onClient(() -> ((BankAccountSelectionScreen.AccountButton) row.handle()).getAccountData().accountNumber);
        Gui.click(row);
        if (!Gui.waitUntil(() -> !(net.kroia.modutilities.gui.client.ClientCompat.screen() instanceof BankAccountSelectionScreen), 20))
            return fail("screen still open after clicking " + row);
        return assertEquals(expected, selected.get());
    }
}
