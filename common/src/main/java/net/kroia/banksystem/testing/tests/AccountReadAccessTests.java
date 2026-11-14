package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task #103: pure-logic tests for the account-data read gate in AsyncBankManager.
 * Covers the shared access rule ({@link BankSystemGenericRequest#isAccessAllowed}), the
 * membership check on {@link BankAccountData} and the reduced names-only user-map copy.
 * A null player sender (server-internal call) is passed to the rule as admin.
 */
public class AccountReadAccessTests extends TestSuite {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000a103");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000b103");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-00000000c103");

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.NETWORKING;
    }

    @Override
    public boolean needsGame() {
        return false; // passes offline in gradlew :common:test
    }

    @Override
    public void setup() {
        SharedConstants.tryDetectVersion(); // offline JUnit run: registries for ItemID/ItemStack; no-op in game
        Bootstrap.bootStrap();
    }

    @Override
    public void registerTests() {
        addTest("read_rule_member_and_admin", this::readRuleMemberAndAdmin);
        addTest("read_rule_untrusted_slave_no_admin_shortcut", this::readRuleUntrustedSlave);
        addTest("read_rule_null_sender", this::readRuleNullSender);
        addTest("account_data_has_user", this::accountDataHasUser);
        addTest("user_map_names_only_copy", this::userMapNamesOnly);
        // Task #104
        addTest("self_or_full_read_admin_query", this::selfOrFullRead);
        addTest("user_names_only_copy", this::userNamesOnly);
        addTest("company_stats_reduced_for_non_members", this::statsReduced);
        addTest("player_accounts_without_permission_bits", this::accountsWithoutBits);
    }

    private TestResult readRuleMemberAndAdmin() {
        TestResult res = assertEquals("member", true, BankSystemGenericRequest.isAccessAllowed(true, false, false));
        if (!res.passed()) return res;
        res = assertEquals("admin non-member", true, BankSystemGenericRequest.isAccessAllowed(false, true, false));
        if (!res.passed()) return res;
        return assertEquals("non-member non-admin", false, BankSystemGenericRequest.isAccessAllowed(false, false, false));
    }

    private TestResult readRuleUntrustedSlave() {
        TestResult res = assertEquals("admin via untrusted slave", false, BankSystemGenericRequest.isAccessAllowed(false, true, true));
        if (!res.passed()) return res;
        return assertEquals("member via untrusted slave", true, BankSystemGenericRequest.isAccessAllowed(true, false, true));
    }

    private TestResult readRuleNullSender() {
        // Null sender: not a member of anything, treated as admin.
        TestResult res = assertEquals("null sender, trusted slave", true, BankSystemGenericRequest.isAccessAllowed(false, true, false));
        if (!res.passed()) return res;
        return assertEquals("null sender, untrusted slave", false, BankSystemGenericRequest.isAccessAllowed(false, true, true));
    }

    private static BankAccountData account() {
        Map<UUID, BankUserData> users = new HashMap<>();
        users.put(MEMBER, new BankUserData(MEMBER, "Member", 1));
        return new BankAccountData(42, "Acc", null, new UserData(OWNER, "Owner", new CompoundTag()), users, Map.of());
    }

    private TestResult accountDataHasUser() {
        BankAccountData acc = account();
        if (!acc.hasUser(OWNER)) return fail("owner not recognised");
        if (!acc.hasUser(MEMBER)) return fail("member not recognised");
        if (acc.hasUser(OUTSIDER)) return fail("outsider recognised as member");
        if (acc.hasUser(null)) return fail("null sender recognised as member");
        return pass("hasUser covers owner and members only");
    }

    private TestResult userMapNamesOnly() {
        CompoundTag secret = new CompoundTag();
        secret.putString("k", "v");
        Map<UUID, UserData> users = new HashMap<>();
        users.put(OWNER, new UserData(OWNER, "Owner", secret));
        users.put(MEMBER, new UserData(MEMBER, "Member", new CompoundTag()));
        Map<Integer, BankAccountData> accounts = new HashMap<>();
        accounts.put(42, account());
        BankManagerData full = new BankManagerData(new BankManagerData.UserMapData(users),
                new BankManagerData.BankAccountsData(accounts), List.of(), List.of());

        BankManagerData reduced = full.namesOnly();
        Map<UUID, UserData> map = reduced.userMapData().userMap();
        if (map.size() != 2) return fail("expected 2 users, got " + map.size());
        if (!"Owner".equals(map.get(OWNER).userName()) || !"Member".equals(map.get(MEMBER).userName()))
            return fail("names lost");
        if (!OWNER.equals(map.get(OWNER).userUUID())) return fail("uuid lost");
        if (!map.get(OWNER).customData().isEmpty()) return fail("custom data leaked");
        if (!reduced.bankAccountsData().bankAccountMap().isEmpty()) return fail("account data leaked");
        if (full.userMapData().userMap().get(OWNER).customData().isEmpty()) return fail("original was modified");
        return pass("names-only copy keeps names and UUIDs, drops accounts and custom data");
    }

    // ── Task #104 ─────────────────────────────────────────────────────────

    private TestResult selfOrFullRead() {
        TestResult res = assertEquals("self", true, BankSystemGenericRequest.isSelfOrFullRead(MEMBER, MEMBER, false));
        if (!res.passed()) return res;
        res = assertEquals("other, no admin", false, BankSystemGenericRequest.isSelfOrFullRead(MEMBER, OWNER, false));
        if (!res.passed()) return res;
        res = assertEquals("other, admin", true, BankSystemGenericRequest.isSelfOrFullRead(MEMBER, OWNER, true));
        if (!res.passed()) return res;
        // Null sender: full read on master/trusted slave, nothing via an untrusted slave.
        res = assertEquals("null sender, full read", true, BankSystemGenericRequest.isSelfOrFullRead(null, OWNER, true));
        if (!res.passed()) return res;
        return assertEquals("null sender, untrusted slave", false, BankSystemGenericRequest.isSelfOrFullRead(null, OWNER, false));
    }

    private TestResult userNamesOnly() {
        User user = new User(OWNER, "Owner");
        user.setBanksystemAdmin(true);
        CompoundTag secret = new CompoundTag();
        secret.putString("k", "v");
        user.setCustomData(secret);
        User reduced = user.namesOnly();
        if (!OWNER.equals(reduced.getUUID()) || !"Owner".equals(reduced.getName())) return fail("uuid or name lost");
        if (reduced.isBanksystemAdmin()) return fail("admin flag leaked");
        if (!reduced.getCustomData().isEmpty()) return fail("custom data leaked");
        if (!user.isBanksystemAdmin() || user.getCustomData().isEmpty()) return fail("original was modified");
        return pass("names-only user keeps uuid and name, drops admin flag and custom data");
    }

    private TestResult statsReduced() {
        AsyncCompanyManager.CompanyStatsPayload r = AsyncCompanyManager.CompanyStatsPayload.reducedFor(100L, 2);
        if (!r.reduced()) return fail("not marked reduced");
        if (r.sharesIssued() != 100L || r.holderCount() != 2) return fail("share or holder count lost");
        if (r.currentBalance() != 0L || r.totalEarnings() != 0L || r.totalSpendings() != 0L
                || r.netCashflow() != 0L || r.missedPayoutAmount() != 0L || r.missedPayoutCount() != 0)
            return fail("financials leaked");
        if (!r.cashflowSeries().isEmpty() || !r.topShareholders().isEmpty()) return fail("series or shareholders leaked");
        return pass("reduced stats hold share and holder counts only");
    }

    private TestResult accountsWithoutBits() {
        AsyncCompanyManager.ListPlayerAccountsOutput full = new AsyncCompanyManager.ListPlayerAccountsOutput(
                AsyncCompanyManager.CODE_OK, List.of(new AsyncCompanyManager.AccountEntry(42, "Acc", 7)));
        AsyncCompanyManager.ListPlayerAccountsOutput stripped = full.withoutPermissionBits();
        if (stripped.resultCode() != AsyncCompanyManager.CODE_OK) return fail("result code lost");
        AsyncCompanyManager.AccountEntry e = stripped.accounts().get(0);
        if (e.accountId() != 42 || !"Acc".equals(e.accountName())) return fail("account number or name lost");
        if (e.filterBits() != 0) return fail("permission bits leaked");
        return pass("account list keeps number and name, drops permission bits");
    }
}
