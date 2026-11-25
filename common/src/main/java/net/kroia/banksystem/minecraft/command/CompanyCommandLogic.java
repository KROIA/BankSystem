package net.kroia.banksystem.minecraft.command;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.util.BankSystemPermissions;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.PermissionCompat;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Master-side logic for {@code /company create|transfer|dissolve|description|info}
 * (Task #43, v2.1.0 Phase 1). Task #43g wired slave-side ARRS dispatch so slaves
 * forward every subcommand to master via {@link AsyncCompanyManager}. Task #43h
 * switched every subcommand except {@code create} to take a company <em>name</em>
 * instead of the internal id.
 */
public final class CompanyCommandLogic {

    private CompanyCommandLogic() {}

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private static IServerBankManager sync() {
        IBankManager mgr = BankSystemMod.getAPI().getServerBankManager();
        if (mgr == null) return null;
        return mgr.getSync();
    }

    /** True when this server has no master-side Company state — must forward to master. */
    private static boolean isSlave() {
        return CompanyManager.get() == null || sync() == null;
    }

    private static void send(ServerPlayer player, String msg) {
        ServerPlayerUtilities.printToClientConsole(player, msg);
    }

    /** Chat text of {@code message.banksystem.company.<key>}. */
    private static String text(String key) {
        return Component.translatable("message." + BankSystemMod.MOD_ID + ".company." + key).getString();
    }

    private static String notFound(String companyName) {
        return text("not_found").replace("{company}", companyName);
    }

    private static String created(long companyId, String name, int accountNr, long maxSupply) {
        return text("created").replace("{company_id}", String.valueOf(companyId)).replace("{account_number}", String.valueOf(accountNr))
                .replace("{amount}", String.valueOf(maxSupply)).replace("{company}", name); // player text last
    }

    private static String createFailed(String name, String reason) {
        return text("create_failed").replace("{reason}", reason).replace("{company}", name);
    }

    /** Issue #85: a slave request that got no answer from the bank master completes with null. */
    private static String noAnswer() {
        return text("no_answer");
    }

    private static String cleanName(String raw) {
        return raw == null ? "" : raw.replace("\"", "").trim();
    }

    // ------------------------------------------------------------------
    // /company create <name> <maxSupply>
    // ------------------------------------------------------------------
    public static void create(ServerPlayer player, String name, long maxSupply) {
        if (isSlave()) {
            AsyncCompanyManager.createCompanyAsync(name, maxSupply,
                            player.getUUID(), player.getName().getString())
                    .thenAccept(out -> renderCreate(player, name, maxSupply, out));
            return;
        }
        IServerBankManager bm = sync();
        CompanyManager cm = CompanyManager.get();

        IServerBankAccount account = bm.createBankAccount(name);
        if (account == null) {
            send(player, text("account_failed").replace("{company}", name));
            return;
        }
        // Company bank accounts need the same default money/currency slots as personal
        // accounts (Task #57) — createBankAccount() alone leaves the account empty.
        net.kroia.banksystem.banking.bankmanager.ServerBankManager.addDefaultBankSlots(account);
        User callerUser = bm.getUserByUUID(player.getUUID());
        if (callerUser == null) {
            bm.addUser(new User(player.getUUID(), player.getName().getString()));
            callerUser = bm.getUserByUUID(player.getUUID());
        }
        if (callerUser != null) {
            account.addUser(callerUser, BankPermission.MANAGE.getValue());
        }

        CompanyManager.CreateOutcome outcome =
                cm.createCompany(name, account.getAccountNumber(), player.getUUID(), maxSupply);
        if (outcome.result != CompanyManager.CreateResult.OK) {
            bm.deleteBankAccount(account.getAccountNumber());
            String capMsg = capMessageFor(outcome.result);
            send(player, capMsg != null ? capMsg : createFailed(name, outcome.result.name()));
            return;
        }
        account.setCreatorUUID(player.getUUID()); // Task #58 — company account counts against the founder's cap
        send(player, created(outcome.company.getCompanyId(), name, account.getAccountNumber(), maxSupply));
    }

    private static void renderCreate(ServerPlayer player, String name, long maxSupply, AsyncCompanyManager.CreateOutput out) {
        if (out == null) {
            send(player, text("create_no_answer").replace("{company}", name));
            return;
        }
        if (out.resultCode() == AsyncCompanyManager.CODE_OK) {
            send(player, created(out.companyId(), name, out.bankAccountNr(), maxSupply));
        } else {
            String capMsg = capMessageForCode(out.resultCode());
            send(player, capMsg != null ? capMsg : createFailed(name, describeCode(out.resultCode())));
        }
    }

    // ------------------------------------------------------------------
    // Task #58 — per-player cap rejection messages (localized)
    // ------------------------------------------------------------------
    /** Master path: maps the two cap {@link CompanyManager.CreateResult}s to a localized chat line. */
    private static String capMessageFor(CompanyManager.CreateResult result) {
        return switch (result) {
            case PER_PLAYER_COMPANY_LIMIT ->
                    BankSystemTextMessages.getCapCompaniesReachedMessage(CompanyManager.perPlayerCompanyCap());
            case PER_PLAYER_ACCOUNT_LIMIT ->
                    BankSystemTextMessages.getCapCompanyBlockedByAccountLimitMessage(CompanyManager.perPlayerAccountCap());
            default -> null;
        };
    }

    /** Slave path: maps the two cap result codes to a localized chat line. */
    private static String capMessageForCode(int code) {
        if (code == AsyncCompanyManager.CODE_PER_PLAYER_COMPANY_LIMIT)
            return BankSystemTextMessages.getCapCompaniesReachedMessage(CompanyManager.perPlayerCompanyCap());
        if (code == AsyncCompanyManager.CODE_PER_PLAYER_ACCOUNT_LIMIT)
            return BankSystemTextMessages.getCapCompanyBlockedByAccountLimitMessage(CompanyManager.perPlayerAccountCap());
        return null;
    }

    // ------------------------------------------------------------------
    // /company info <companyName>
    // ------------------------------------------------------------------
    public static void info(ServerPlayer player, String rawCompanyName) {
        String companyName = cleanName(rawCompanyName);
        if (isSlave()) {
            AsyncCompanyManager.getCompanyInfoAsync(companyName)
                    .thenAccept(out -> renderInfo(player, companyName, out));
            return;
        }
        CompanyManager cm = CompanyManager.get();
        Company company = cm.getByName(companyName);
        if (company == null) {
            send(player, notFound(companyName));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§8============================================\n");
        appendInfoHeader(sb, company.getCompanyId(), company.getName(), company.getBankAccountNr(),
                company.getMaxSupply(), company.getTotalSharesIssued());
        Set<UUID> founders = company.getFounders();
        appendFoundersLine(sb, founders.size());
        IServerBankManager bm = sync();
        for (UUID uuid : founders) {
            User u = bm.getUserByUUID(uuid);
            sb.append("  §f").append(u != null ? u.getName() : uuid.toString()).append('\n');
        }
        if (!company.getDescription().isEmpty()) {
            sb.append("§7").append(text("info_description").replace("{description}", "§f" + company.getDescription())).append('\n');
        }
        sb.append("§8============================================");
        send(player, sb.toString());
    }

    /** Company/bank account/max supply/issued lines of {@code /company info}: grey label, white value. */
    private static void appendInfoHeader(StringBuilder sb, long companyId, String name, int accountNr, long maxSupply, long issued) {
        sb.append("§7").append(text("info_title").replace("{company_id}", String.valueOf(companyId))
                .replace("{company}", "§f" + name)).append('\n');
        sb.append("§7").append(text("info_bank_account").replace("{account_number}", "§f" + accountNr)).append('\n');
        sb.append("§7").append(text("info_max_supply").replace("{amount}", "§f" + maxSupply)).append('\n');
        sb.append("§7").append(text("info_issued").replace("{amount}", "§f" + issued)).append('\n');
    }

    private static void appendFoundersLine(StringBuilder sb, int count) {
        sb.append("§7").append(text("info_founders").replace("{count}", String.valueOf(count))).append('\n');
    }

    private static void renderInfo(ServerPlayer player, String companyName, AsyncCompanyManager.CompanyInfoOutput out) {
        if (out == null) { send(player, noAnswer()); return; }
        if (!out.present()) {
            send(player, notFound(companyName));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§8============================================\n");
        appendInfoHeader(sb, out.companyId(), out.name(), out.bankAccountNr(), out.maxSupply(), out.totalSharesIssued());
        List<String> founders = out.founderNames();
        appendFoundersLine(sb, founders.size());
        for (String fname : founders) {
            sb.append("  §f").append(fname).append('\n');
        }
        if (out.description() != null && !out.description().isEmpty()) {
            sb.append("§7").append(text("info_description").replace("{description}", "§f" + out.description())).append('\n');
        }
        sb.append("§8============================================");
        send(player, sb.toString());
    }

    // ------------------------------------------------------------------
    // /company manage <companyName>  (Task #51, v2.1.0)
    // ------------------------------------------------------------------
    /**
     * Resolves company by name, checks MANAGE, and if OK dispatches
     * {@link net.kroia.banksystem.networking.general.S2COpenCompanyManagementPacket}
     * to open the client management screen.
     */
    public static void manage(ServerPlayer player, String rawCompanyName) {
        String companyName = cleanName(rawCompanyName);
        if (isSlave()) {
            AsyncCompanyManager.getCompanyInfoAsync(companyName).thenAccept(out -> {
                if (out == null) { send(player, noAnswer()); return; }
                if (!out.present()) {
                    send(player, notFound(companyName));
                    return;
                }
                // Slave has no local bank-account view of MANAGE — rely on master to
                // gate any mutating follow-up. For opening the screen we optimistically
                // send; the screen's actions themselves route through MANAGE-gated ARRS.
                net.kroia.banksystem.networking.general.S2COpenCompanyManagementPacket
                        .send(player, out.companyId(), out.name());
            });
            return;
        }
        CompanyManager cm = CompanyManager.get();
        IServerBankManager bm = sync();
        Company company = cm.getByName(companyName);
        if (company == null) {
            send(player, notFound(companyName));
            return;
        }
        IServerBankAccount account = bm.getBankAccount(company.getBankAccountNr());
        boolean hasManage = account != null && account.hasPermission(player.getUUID(), BankPermission.MANAGE);
        boolean isAdmin = bm.isBanksystemAdmin(player.getUUID());
        if (!hasManage && !isAdmin) {
            send(player, text("manage_no_permission"));
            return;
        }
        net.kroia.banksystem.networking.general.S2COpenCompanyManagementPacket
                .send(player, company.getCompanyId(), company.getName());
    }

    // ------------------------------------------------------------------
    // /company shares acknowledge <companyName>  (BS-235)
    // ------------------------------------------------------------------
    /**
     * Accepts the company's share excess and resumes its dividends. The company admin check
     * ({@link AsyncCompanyManager#gateManage}) runs on the master; slaves forward. The master
     * console / a command block (op level) acts as a BankSystem admin.
     */
    public static void sharesAcknowledge(CommandSourceStack source, String rawCompanyName) {
        String companyName = cleanName(rawCompanyName);
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            if (isSlave() || !PermissionCompat.hasPermission(source, BankSystemPermissions.FALLBACK_LEVEL)) {
                source.sendFailure(Component.literal("[BankSystem] From the console this works on the master server only."));
                return;
            }
            source.sendSystemMessage(Component.literal(sharesAcknowledgeText(companyName,
                    AsyncCompanyManager.acknowledgeShareExcess(companyName, null, sync(), CompanyManager.get()))));
            return;
        }
        if (isSlave()) {
            AsyncCompanyManager.acknowledgeShareExcessAsync(companyName, player.getUUID())
                    .thenAccept(out -> send(player, sharesAcknowledgeText(companyName, out)));
            return;
        }
        send(player, sharesAcknowledgeText(companyName,
                AsyncCompanyManager.acknowledgeShareExcess(companyName, player.getUUID(), sync(), CompanyManager.get())));
    }

    private static String sharesAcknowledgeText(String companyName, AsyncCompanyManager.ShareAcknowledgeOutput out) {
        if (out == null) return noAnswer();
        return switch (out.resultCode()) {
            case AsyncCompanyManager.CODE_OK -> text("shares_acknowledged").replace("{amount}", String.valueOf(out.excess()))
                    .replace("{company}", out.companyName());
            case AsyncCompanyManager.CODE_NOT_FOUND -> notFound(companyName);
            case AsyncCompanyManager.CODE_NO_PERMISSION -> text("shares_no_permission");
            default -> text("shares_failed").replace("{reason}", describeCode(out.resultCode())).replace("{company}", companyName);
        };
    }

    // ------------------------------------------------------------------
    // Code labels for slave-side error rendering
    // ------------------------------------------------------------------
    private static String describeCode(int code) {
        return switch (code) {
            case AsyncCompanyManager.CODE_OK -> "OK";
            case AsyncCompanyManager.CODE_NOT_FOUND -> "NOT_FOUND";
            case AsyncCompanyManager.CODE_NAME_TAKEN -> "NAME_TAKEN";
            case AsyncCompanyManager.CODE_INVALID_INPUT -> "INVALID_INPUT";
            case AsyncCompanyManager.CODE_NOT_FOUNDER -> "NOT_FOUNDER";
            case AsyncCompanyManager.CODE_ALREADY_FOUNDER -> "ALREADY_FOUNDER";
            case AsyncCompanyManager.CODE_MISSING_TARGET -> "MISSING_TARGET";
            case AsyncCompanyManager.CODE_NOT_A_WORKER -> "NOT_A_WORKER";
            case AsyncCompanyManager.CODE_NO_PERMISSION -> "NO_PERMISSION";
            case AsyncCompanyManager.CODE_BANK_ACCOUNT_ERROR -> "BANK_ACCOUNT_ERROR";
            case AsyncCompanyManager.CODE_INTERNAL -> "INTERNAL";
            default -> "UNKNOWN(" + code + ")";
        };
    }
}
