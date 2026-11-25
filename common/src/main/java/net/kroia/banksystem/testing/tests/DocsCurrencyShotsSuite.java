package net.kroia.banksystem.testing.tests;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.integration.numismatics.NumismaticsProvider;
import net.kroia.banksystem.networking.currency.BindExternalAccountRequest;
import net.kroia.banksystem.networking.currency.ListBindableAccountsRequest;
import net.kroia.banksystem.screen.custom.BankAccountBindingsScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.gui.Gui;
import net.kroia.testkit.gui.GuiNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * BS-291: the two Currency Bindings pictures (Docs/planning/images.md: currency-bindings, currency-bind-dialog).
 * Needs Create: Numismatics, so it runs in the Prism instance "1.21.1 currencyIntegration_numismatics" (NeoForge),
 * not in a test cell; same singleplayer setup as {@link DocsScreenshotSuite} (client named Tavrik, its fixture).
 * <p>
 * Tavrik's Numismatics personal account gets a balance and Savings (Tavrik's personal account) a Spur slot. The
 * dialog shot opens <b>Bind...</b> on the Spur row (the account picker); then the slot is bound to that account and
 * the bindings screen is shot. On demand: {@code banksystem/bs_docs_shots/DocsCurrencyShotsSuite}.
 */
public class DocsCurrencyShotsSuite extends DocsScreenshotSuite {

    private static final String SPUR = "numismatics:spur";
    /** Spurs in Tavrik's Numismatics account and in the Spur slot; binding moves the slot's spurs over (1'250 in all). */
    private static final int NUMISMATICS_SPURS = 1_186, SLOT_SPURS = 64;

    private static @Nullable ItemID spur;
    private static String spurProblem = "Spur slot not set up yet";

    @Override
    public void registerTests() {
        addTest("fixture", this::test_fixture);
        addTest("numismatics_account", this::test_numismatics);
        // the dialog first: once bound, the Spur row shows Unbind instead of Bind...
        addTest("currency_bind_dialog", () -> spur == null ? skip(spurProblem) : shot("banksystem/currency-bind-dialog.png", this::bindDialog));
        addTest("currency_bindings", () -> spur == null ? skip(spurProblem) : shot("banksystem/currency-bindings.png", this::bindings));
    }

    /** Tavrik's Numismatics balance and the Spur slot in Savings. */
    private TestResult test_numismatics() {
        if (fixture == null) return skip(String.valueOf(fixtureProblem));
        if (!Platform.isModLoaded("numismatics"))
            return skip(spurProblem = "Create: Numismatics not loaded (Prism instance 1.21.1 currencyIntegration_numismatics)");
        try {
            UUID self = fixture.self();
            onServer(() -> {
                try { // Numismatics through reflection, like NumismaticsProvider: BS has no compile dependency on it
                    Class<?> type = Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount$Type");
                    Object bank = Class.forName("dev.ithundxr.createnumismatics.Numismatics").getField("BANK").get(null);
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object account = bank.getClass().getMethod("getOrCreateAccount", UUID.class, type)
                            .invoke(bank, self, Enum.valueOf((Class<Enum>) type, "PLAYER"));
                    account.getClass().getMethod("setBalance", int.class).invoke(account, NUMISMATICS_SPURS);
                    return null;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Numismatics account: " + e, e);
                }
            });
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(SPUR)));
            IAsyncBankManager bank = BankSystemMod.getAPI().getServerBankManager().getAsync();
            ItemID id = await(ItemIDManager.registerItemStackServerSide(stack), WAIT_MS);
            await(bank.allowItemIDAsync(id), WAIT_MS);
            String error = setBalance(account(bank, fixture.savings()), id, SLOT_SPURS);
            if (error != null) return fail(spurProblem = error);
            spur = id;
            return pass("Numismatics " + NUMISMATICS_SPURS + " spurs, Spur slot " + SLOT_SPURS + " in Savings");
        } catch (RuntimeException e) {
            return fail(spurProblem = "numismatics setup: " + e);
        }
    }

    /** The account picker of Bind... on the Spur row. */
    private @Nullable String bindDialog() {
        openBindings();
        String bind = text("gui.banksystem.bank_account_bindings_screen.bind_button");
        GuiNode button = findIn(spurRow(), n -> n.visible() && bind.equals(n.text()));
        if (button == null) return "no " + bind + " on the Spur row (already bound?)";
        Gui.click(button);
        Gui.waitTicks(DATA_TICKS); // the picker asks the server for the accounts
        String picker = text("gui.banksystem.bank_account_bindings_screen.pick_account");
        if (Gui.findAll(n -> n.visible() && n.text() != null && n.text().startsWith(picker)).isEmpty())
            return "the account picker did not open";
        if (Gui.findAll(n -> n.visible() && "Personal Account".equals(n.text())).isEmpty())
            return "the picker shows no Personal Account";
        return null;
    }

    /** Binds the Spur slot to Tavrik's Numismatics account (the request the picker's confirm sends), then the screen. */
    private @Nullable String bindings() {
        UUID self = fixture.self();
        List<ExternalAccountRef> refs = await(Gui.onClient(() ->
                ListBindableAccountsRequest.sendRequest(NumismaticsProvider.PROVIDER_ID, self)), WAIT_MS);
        ExternalAccountRef personal = refs == null ? null : refs.stream().filter(r -> !r.shared()).findFirst().orElse(null);
        if (personal == null) return "no Numismatics personal account in " + refs;
        BankStatus status = await(Gui.onClient(() ->
                BindExternalAccountRequest.sendRequest(fixture.savings(), spur, personal)), WAIT_MS);
        if (status != BankStatus.SUCCESS) return "bind refused: " + status;
        openBindings();
        if (findIn(spurRow(), n -> n.visible() && n.text() != null && n.text().contains("Personal Account")) == null)
            return "the Spur row does not show the binding";
        return null;
    }

    private void openBindings() {
        Gui.onClient(() -> BankAccountBindingsScreen.openScreen(null, fixture.savings(), false, true));
        Gui.waitForScreen(BankAccountBindingsScreen.class, 100);
        Gui.waitTicks(DATA_TICKS);
    }

    private static GuiNode spurRow() {
        return Gui.find(n -> n.visible() && "BindingRow".equals(n.type()) && findIn(n, c -> SPUR.equals(c.text())) != null,
                "Spur row");
    }

    private static String text(String key) {
        return Gui.onClient(() -> Component.translatable(key).getString());
    }
}
