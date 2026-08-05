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
import net.kroia.modutilities.ServerPlayerUtilities;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Master-side logic for {@code /company create|transfer|dissolve|description|info}
 * (Task #43, v2.0.8 Phase 1). Task #43g wired slave-side ARRS dispatch so slaves
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
            send(player, "Failed to create bank account for company '" + name + "'.");
            return;
        }
        User callerUser = bm.getUserByUUID(player.getUUID());
        if (callerUser == null) {
            bm.addUser(new User(player.getUUID(), player.getName().getString(), true));
            callerUser = bm.getUserByUUID(player.getUUID());
        }
        if (callerUser != null) {
            account.addUser(callerUser, BankPermission.MANAGE.getValue());
        }

        CompanyManager.CreateOutcome outcome =
                cm.createCompany(name, account.getAccountNumber(), player.getUUID(), maxSupply);
        if (outcome.result != CompanyManager.CreateResult.OK) {
            bm.deleteBankAccount(account.getAccountNumber());
            send(player, "Failed to create company '" + name + "': " + outcome.result.name());
            return;
        }
        send(player, "Created company #" + outcome.company.getCompanyId() + " '" + name
                + "' bound to bank account " + account.getAccountNumber()
                + " (maxSupply=" + maxSupply + ").");
    }

    private static void renderCreate(ServerPlayer player, String name, long maxSupply, AsyncCompanyManager.CreateOutput out) {
        if (out.resultCode() == AsyncCompanyManager.CODE_OK) {
            send(player, "Created company #" + out.companyId() + " '" + name
                    + "' bound to bank account " + out.bankAccountNr()
                    + " (maxSupply=" + maxSupply + ").");
        } else {
            send(player, "Failed to create company '" + name + "': " + describeCode(out.resultCode()));
        }
    }

    // ------------------------------------------------------------------
    // /company transfer <companyName> <newFounder>
    // ------------------------------------------------------------------
    public static void transfer(ServerPlayer player, String rawCompanyName, String newFounderName) {
        String companyName = cleanName(rawCompanyName);
        if (isSlave()) {
            AsyncCompanyManager.transferFounderAsync(companyName, player.getUUID(), newFounderName)
                    .thenAccept(out -> renderTransfer(player, companyName, newFounderName, out));
            return;
        }
        CompanyManager cm = CompanyManager.get();
        IServerBankManager bm = sync();

        Company company = cm.getByName(companyName);
        if (company == null) {
            send(player, "No such company '" + companyName + "'.");
            return;
        }
        if (!company.isFounder(player.getUUID())) {
            send(player, "Only a founder of company '" + companyName + "' can transfer it.");
            return;
        }
        String cleaned = cleanName(newFounderName);
        User target = bm.getUserByName(cleaned);
        if (target == null) {
            send(player, "No known user named '" + cleaned + "'.");
            return;
        }
        UUID from = player.getUUID();
        UUID to = target.getUUID();
        CompanyManager.TransferResult r = cm.transferFounder(company.getCompanyId(), from, to);
        switch (r) {
            case OK -> send(player, "Transferred founder of company '" + company.getName()
                    + "' from " + player.getName().getString() + " to " + target.getName() + ".");
            case COMPANY_MISSING -> send(player, "No such company '" + companyName + "'.");
            case NOT_A_FOUNDER -> send(player, "You are not a founder of company '" + companyName + "'.");
            case ALREADY_A_FOUNDER -> send(player, target.getName() + " is already a founder.");
        }
    }

    private static void renderTransfer(ServerPlayer player, String companyName, String targetName, AsyncCompanyManager.TransferOutput out) {
        switch (out.resultCode()) {
            case AsyncCompanyManager.CODE_OK -> send(player, "Transferred founder of company '" + companyName
                    + "' from " + out.fromName() + " to " + out.toName() + ".");
            case AsyncCompanyManager.CODE_NOT_FOUND -> send(player, "No such company '" + companyName + "'.");
            case AsyncCompanyManager.CODE_NOT_FOUNDER -> send(player, "You are not a founder of company '" + companyName + "'.");
            case AsyncCompanyManager.CODE_ALREADY_FOUNDER -> send(player, out.toName() + " is already a founder.");
            case AsyncCompanyManager.CODE_MISSING_TARGET -> send(player, "No known user named '" + cleanName(targetName) + "'.");
            default -> send(player, "Failed to transfer company '" + companyName + "': " + describeCode(out.resultCode()));
        }
    }

    // ------------------------------------------------------------------
    // /company dissolve <companyName>
    // ------------------------------------------------------------------
    public static void dissolve(ServerPlayer player, String rawCompanyName) {
        String companyName = cleanName(rawCompanyName);
        if (isSlave()) {
            AsyncCompanyManager.dissolveCompanyAsync(companyName, player.getUUID())
                    .thenAccept(out -> renderDissolve(player, companyName, out));
            return;
        }
        CompanyManager cm = CompanyManager.get();
        Company company = cm.getByName(companyName);
        if (company == null) {
            send(player, "No such company '" + companyName + "'.");
            return;
        }
        if (!company.isFounder(player.getUUID())) {
            send(player, "Only a founder may dissolve company '" + company.getName() + "'.");
            return;
        }
        int id = company.getCompanyId();
        String name = company.getName();
        int accNr = company.getBankAccountNr();
        if (cm.deleteCompany(id)) {
            send(player, "Dissolved company '" + name
                    + "'. Bank account " + accNr + " remains as a multi-user account.");
        } else {
            send(player, "Failed to dissolve company '" + name + "'.");
        }
    }

    private static void renderDissolve(ServerPlayer player, String companyName, AsyncCompanyManager.DissolveOutput out) {
        switch (out.resultCode()) {
            case AsyncCompanyManager.CODE_OK -> send(player, "Dissolved company '" + out.companyName()
                    + "'. Bank account " + out.bankAccountNr() + " remains as a multi-user account.");
            case AsyncCompanyManager.CODE_NOT_FOUND -> send(player, "No such company '" + companyName + "'.");
            case AsyncCompanyManager.CODE_NOT_FOUNDER -> send(player, "Only a founder may dissolve company '" + companyName + "'.");
            default -> send(player, "Failed to dissolve company '" + companyName + "': " + describeCode(out.resultCode()));
        }
    }

    // ------------------------------------------------------------------
    // /company description <companyName> <text>
    // ------------------------------------------------------------------
    public static void description(ServerPlayer player, String rawCompanyName, String text) {
        String companyName = cleanName(rawCompanyName);
        if (isSlave()) {
            AsyncCompanyManager.updateDescriptionAsync(companyName, player.getUUID(), text)
                    .thenAccept(out -> renderDescription(player, companyName, out));
            return;
        }
        CompanyManager cm = CompanyManager.get();
        IServerBankManager bm = sync();
        Company company = cm.getByName(companyName);
        if (company == null) {
            send(player, "No such company '" + companyName + "'.");
            return;
        }
        IServerBankAccount account = bm.getBankAccount(company.getBankAccountNr());
        boolean hasManage = account != null && account.hasPermission(player.getUUID(), BankPermission.MANAGE);
        boolean isAdmin = bm.isBanksystemAdmin(player.getUUID());
        if (!hasManage && !isAdmin) {
            send(player, "You need MANAGE on the company's bank account to edit the description.");
            return;
        }
        cm.updateDescription(company.getCompanyId(), text);
        send(player, "Updated description of company '" + company.getName() + "'.");
    }

    private static void renderDescription(ServerPlayer player, String companyName, AsyncCompanyManager.DescriptionOutput out) {
        switch (out.resultCode()) {
            case AsyncCompanyManager.CODE_OK -> send(player, "Updated description of company '" + companyName + "'.");
            case AsyncCompanyManager.CODE_NOT_FOUND -> send(player, "No such company '" + companyName + "'.");
            case AsyncCompanyManager.CODE_NO_PERMISSION -> send(player, "You need MANAGE on the company's bank account to edit the description.");
            default -> send(player, "Failed to update description of company '" + companyName + "': " + describeCode(out.resultCode()));
        }
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
            send(player, "No such company '" + companyName + "'.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§8============================================\n");
        sb.append("§7Company #").append(company.getCompanyId()).append(" §f").append(company.getName()).append('\n');
        sb.append("§7Bank account: §f").append(company.getBankAccountNr()).append('\n');
        sb.append("§7Max supply:   §f").append(company.getMaxSupply()).append('\n');
        sb.append("§7Issued:       §f").append(company.getTotalSharesIssued()).append('\n');
        Set<UUID> founders = company.getFounders();
        sb.append("§7Founders (").append(founders.size()).append("):\n");
        IServerBankManager bm = sync();
        for (UUID uuid : founders) {
            User u = bm.getUserByUUID(uuid);
            sb.append("  §f").append(u != null ? u.getName() : uuid.toString()).append('\n');
        }
        if (!company.getDescription().isEmpty()) {
            sb.append("§7Description: §f").append(company.getDescription()).append('\n');
        }
        sb.append("§8============================================");
        send(player, sb.toString());
    }

    private static void renderInfo(ServerPlayer player, String companyName, AsyncCompanyManager.CompanyInfoOutput out) {
        if (!out.present()) {
            send(player, "No such company '" + companyName + "'.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§8============================================\n");
        sb.append("§7Company #").append(out.companyId()).append(" §f").append(out.name()).append('\n');
        sb.append("§7Bank account: §f").append(out.bankAccountNr()).append('\n');
        sb.append("§7Max supply:   §f").append(out.maxSupply()).append('\n');
        sb.append("§7Issued:       §f").append(out.totalSharesIssued()).append('\n');
        List<String> founders = out.founderNames();
        sb.append("§7Founders (").append(founders.size()).append("):\n");
        for (String fname : founders) {
            sb.append("  §f").append(fname).append('\n');
        }
        if (out.description() != null && !out.description().isEmpty()) {
            sb.append("§7Description: §f").append(out.description()).append('\n');
        }
        sb.append("§8============================================");
        send(player, sb.toString());
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
            case AsyncCompanyManager.CODE_NO_PERMISSION -> "NO_PERMISSION";
            case AsyncCompanyManager.CODE_BANK_ACCOUNT_ERROR -> "BANK_ACCOUNT_ERROR";
            case AsyncCompanyManager.CODE_INTERNAL -> "INTERNAL";
            default -> "UNKNOWN(" + code + ")";
        };
    }
}
