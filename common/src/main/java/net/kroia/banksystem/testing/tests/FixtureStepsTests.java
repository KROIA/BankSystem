package net.kroia.banksystem.testing.tests;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.fixture.FixtureKey;
import net.kroia.banksystem.testing.fixture.FixtureStepRequest;
import net.kroia.banksystem.testing.fixture.FixtureSteps;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestRegistry;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BS-269 — smoke run of the BankSystem fixture steps A → C9 → C10 → C11 → C13 → C14 → C15 → C16 → D → E (bank) → C12 on a trusted
 * slave, then the key's banknotes as at the copy moment. Opt-in: skips
 * unless named by {@code --only} ({@link TestRegistry#INCLUDE_PROPERTY}), because it leaves testers, flags and a
 * suppressed bank save behind. Cleanup: bank saves on again.
 */
public class FixtureStepsTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.FIXTURE;
    }

    @Override
    public void registerTests() {
        addTest("storySteps", this::storySteps);
    }

    private TestResult storySteps() {
        String include = System.getProperty(TestRegistry.INCLUDE_PROPERTY);
        if (include == null || include.isBlank() || TestRegistry.isExcluded(TestRegistry.topicKey(this), include, null))
            return skip("opt-in: --only banksystem/fixture");
        if (!MultiServerManager.isSlave() || !MultiServerManager.isRunning()) return skip("needs a slave with the master link up");
        if (!Boolean.TRUE.equals(await(BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync()
                .isSlaveServerTrustedAsync(""), 10_000))) return skip("master does not trust this slave");
        FixtureKey key = FixtureKey.start("banksystem/fixture", "dev");
        try {
            FixtureSteps.Background bg = FixtureSteps.background(getServer());
            if (bg.testers().size() != 5 || bg.sharedAccount() <= 0 || bg.companyId() <= 0 || bg.companyAccount() <= 0
                    || bg.transfers().isEmpty()) return fail("background ids missing: " + bg);
            boolean client = FixtureSteps.client(getServer()) != null;
            List<FixtureSteps.Step> steps = List.of(FixtureSteps.balanceGap(getServer()), FixtureSteps.adminCommands(getServer()),
                    FixtureSteps.suspicionFlags(getServer()), FixtureSteps.notes(getServer()), FixtureSteps.shares(getServer()),
                    FixtureSteps.converterAndBlocks(getServer()), FixtureSteps.deletedUser(getServer()), FixtureSteps.transfers(getServer()),
                    FixtureSteps.exploitBank(getServer()), FixtureSteps.lastSave(getServer()));
            FixtureSteps.banknotesAtCopy(getServer());
            boolean noWealth = steps.get(2).note().contains(FixtureSteps.WEALTH_SKIPPED);
            int[] minCases = {2, 6, noWealth ? 16 : 19, 1, 1, 3, client ? 4 : 3, 12, noWealth ? 1 : 2, 1};
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < steps.size(); i++) {
                FixtureSteps.Step s = steps.get(i);
                if (s.cases().size() < minCases[i] || s.ledger().isEmpty()) return fail("step " + i + " made too little: " + s);
                ids.addAll(s.cases());
            }
            if (steps.get(2).flags().size() < (noWealth ? 7 : 9)) return fail("C11 flags: " + steps.get(2).flags());
            JsonObject json = JsonParser.parseString(key.toJson().toString()).getAsJsonObject();
            int cases = json.getAsJsonArray("cases").size();
            if (cases != ids.size()) return fail("key has " + cases + " cases, steps made " + ids.size() + " distinct ids");
            for (JsonElement e : json.getAsJsonArray("cases"))
                if (!ids.contains(e.getAsJsonObject().get("id").getAsString())) return fail("unexpected case " + e);
            // TesterA..E (background) + TesterW (C10) + TesterX, TesterY (C11) + the client (C13, offline-mode uuid only)
            int players = json.getAsJsonArray("players").size();
            if (players != 8 && !(client && players == 9)) return fail("players: " + json.getAsJsonArray("players"));
            if (json.getAsJsonArray("banknotes").size() != (client ? 3 : 1)) return fail("banknotes: " + json.getAsJsonArray("banknotes"));
            if (steps.get(6).flags().size() != 1) return fail("C16 flags: " + steps.get(6).flags());
            if (steps.get(7).flags().size() != 3) return fail("D flags: " + steps.get(7).flags());
            for (JsonElement l : json.getAsJsonArray("links"))
                if (!l.getAsJsonObject().get("a").getAsString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    return fail("link a must be a lowercase uuid: " + l);
            return pass(cases + " cases; " + steps.get(1).note() + "; " + steps.get(2).note() + "; C13 " + steps.get(3).note()
                    + "; C16 " + steps.get(6).note() + "; D " + steps.get(7).note() + "; E " + steps.get(8).note() + "; banknotes " + json.getAsJsonArray("banknotes"));
        } catch (RuntimeException e) {
            return fail("fixture step threw: " + e);
        } finally {
            try {
                await(FixtureStepRequest.call("suppressSave", "{\"on\":false}"), 30_000);
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
    }
}
