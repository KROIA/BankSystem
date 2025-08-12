package net.kroia.banksystem.banking;

/*
public class BankUserOld implements ServerSaveable, IBankUserOld {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private UUID userUUID;
    private String userName;
    private final HashMap<ItemID, Bank> bankMap = new HashMap<>();
    private boolean enableBankNotifications = true;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankUserOld.BACKEND_INSTANCES = backend;
    }
    public BankUserOld(ServerPlayer player)
    {
        this(player.getUUID(), player.getName().getString());
    }
    public BankUserOld(UUID userUUID, String userName) {
        this.userUUID = userUUID;
        this.userName = userName;
    }

    private BankUserOld()
    {

    }
    public static @Nullable BankUserOld loadFromTag(CompoundTag tag)
    {
        BankUserOld user = new BankUserOld();
        if(user.load(tag))
            return user;
        return null;
    }

    @Override
    public MinimalBankUserData getMinimalData()
    {
        return new MinimalBankUserData(this);
    }

    @Override
    public BankData getMinimalBankData(ItemID itemID)
    {
        IBank bank = getBank(itemID);
        if(bank == null)
        {
            return new BankData(itemID, BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(itemID));
        }
        return bank.getMinimalData();
    }

    @Override
    public UUID getPlayerUUID() {
        return userUUID;
    }
    @Override
    public @Nullable ServerPlayer getPlayer()
    {
        return ServerPlayerUtilities.getOnlinePlayer(userUUID);
    }

    @Override
    public String getPlayerName()
    {
        ServerPlayer player = getPlayer();
        if(player != null) {
            userName = player.getName().getString();
        }
        if(userName == null)
            return "UnknownUserName";
        return userName;
    }
    @Override
    public IBank createMoneyBank(float startBalance)
    {
        IBank bank = getBank(MoneyBank.ITEM_ID);
        if(bank != null)
            return bank;
        long startBalanceRaw = MoneyBank.convertToRawAmountStatic(startBalance);
        MoneyBank moneyBank = new MoneyBank(this, startBalanceRaw);
        bankMap.put(MoneyBank.ITEM_ID, moneyBank);
        return moneyBank;
    }
    @Override
    public @Nullable IBank createItemBank(ItemID itemID, float startBalance, boolean notifyPlayerOnFail)
    {
        IBank bank = getBank(itemID);
        if(bank != null)
            return bank;

        // Cehck if it is a money item
        if(MoneyItem.isMoney(itemID))
        {
            return createMoneyBank(startBalance);
        }

        if(!BACKEND_INSTANCES.SERVER_BANK_MANAGER.isItemIDAllowed(itemID))
        {
            if(notifyPlayerOnFail)
                ServerPlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getItemNotAllowedMessage(itemID.getName()));
            return null;
        }
        long rawStartBalance = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(itemID) * (long) startBalance;
        ItemBank itemBank = new ItemBank(this, itemID,  rawStartBalance);
        bankMap.put(itemID, itemBank);
        return itemBank;
    }

    @Override
    public @Nullable IBank getBank(ItemID itemID)
    {
        return bankMap.get(itemID);
    }

    @Override
    public @Nullable Bank getMoneyBank()
    {
        return bankMap.get(MoneyBank.ITEM_ID);
    }
    @Override
    public HashMap<ItemID, IBank> getAllBanks()
    {
        return new HashMap<>(bankMap);
    }

    @Override
    public boolean removeBank(ItemID itemID)
    {
        Bank bank = bankMap.remove(itemID);
        if(bank == null)
            return false;

        ServerPlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getBankDeletedMessage(getPlayerName(), bank.getItemName())+"\n"+
                BankSystemTextMessages.getBankBalanceLostMessage(Bank.getFormattedAmount(bank.getTotalBalance(), bank.getItemFractionScaleFactor()), bank.getItemName()));
        return true;
    }

    @Override
    public List<ItemID> removeEmptyBanks()
    {
        List<ItemID> removedBanks = new ArrayList<>();
        Iterator<Map.Entry<ItemID, Bank>> iterator = bankMap.entrySet().iterator();
        while(iterator.hasNext())
        {
            Map.Entry<ItemID, Bank> entry = iterator.next();
            if(entry.getValue().getTotalBalance() <= 0)
            {
                //PlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getBankDeletedMessage(getPlayerName(), entry.getValue().getItemName())+"\n"+
                //        BankSystemTextMessages.getBankBalanceLostMessage(entry.getValue().getTotalBalance(), entry.getValue().getItemName()));
                removedBanks.add(entry.getKey());
                iterator.remove();
            }
        }
        return removedBanks;
    }

    @Override
    public long getMoneyBalance()
    {
        Bank bank = getMoneyBank();
        if(bank != null)
            return bank.getBalance();
        return 0;
    }

    @Override
    public long getLockedMoneyBalance()
    {
        Bank bank = getMoneyBank();
        if(bank != null)
            return bank.getLockedBalance();
        return 0;
    }

    @Override
    public long getTotalMoneyBalance()
    {
        Bank bank = getMoneyBank();
        if(bank != null)
            return bank.getTotalBalance();
        return 0;
    }
    @Override
    public boolean isBankNotificationEnabled()
    {
        return enableBankNotifications;
    }
    @Override
    public void setBankNotificationEnabled(boolean enabled)
    {
        enableBankNotifications = enabled;
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putUUID("userUUID", userUUID);
        tag.putString("userName", userName);
        tag.putBoolean("enableBankNotifications", enableBankNotifications);


        ListTag bankElements = new ListTag();
        for (Map.Entry<ItemID, Bank> entry : bankMap.entrySet()) {
            CompoundTag bankTag = new CompoundTag();
            entry.getValue().save(bankTag);
            bankElements.add(bankTag);
        }
        tag.put("bankMap", bankElements);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        boolean loadSuccess = true;
        userUUID = tag.getUUID("userUUID");
        userName = tag.getString("userName");
        if(tag.contains("enableBankNotifications"))
            enableBankNotifications = tag.getBoolean("enableBankNotifications");
        else
            enableBankNotifications = true;


        ListTag bankElements = tag.getList("bankMap", 10);
        bankMap.clear();
        for (int i = 0; i < bankElements.size(); i++) {
            CompoundTag bankTag = bankElements.getCompound(i);
            Bank bank = Bank.loadFromTag(this, bankTag);
            if(bank != null) {
                if(!bank.getItemID().isValid())
                    continue;
                bankMap.put(bank.getItemID(), bank);
            }
            else
                loadSuccess = false;
        }
        return loadSuccess;
    }


    @Override
    public JsonElement toJson()
    {
        JsonObject userJson = new JsonObject();

        userJson.addProperty("userUUID", userUUID.toString());
        userJson.addProperty("userName", userName);
        userJson.addProperty("enableBankNotifications", enableBankNotifications);
        JsonArray bankMapJson = new JsonArray();
        for (Map.Entry<ItemID, Bank> entry : bankMap.entrySet()) {
            ItemID itemID = entry.getKey();
            Bank bank = entry.getValue();
            if(bank == null)
                continue;
            JsonElement bankJson = bank.toJson();
            if(bankJson != null) {
                bankMapJson.add(bankJson);
            }
        }
        userJson.add("banks", bankMapJson);
        return userJson;
    }

    @Override
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }


    @Override
    public String toString()
    {
        String owner = getPlayerName();
        StringBuilder content = new StringBuilder(BankSystemTextMessages.getBankOfMessage(owner) + "\n");
        ArrayList<String> itemNames = new ArrayList<>();
        ArrayList<String> itemBalances = new ArrayList<>();

        if(bankMap.containsKey(MoneyBank.ITEM_ID)) {
            itemNames.add(bankMap.get(MoneyBank.ITEM_ID).getItemName());
            itemBalances.add(bankMap.get(MoneyBank.ITEM_ID).getFormattedTotalBalance());
        }

        for(Bank bank : bankMap.values())
        {
            if(bank.getItemID().equals(MoneyBank.ITEM_ID))
                continue;
            itemNames.add(bank.getItemName());
            itemBalances.add(String.valueOf(bank.getFormattedTotalBalance()));
        }
        int maxAmountLength = 0;
        for(String itemName : itemBalances)
        {
            if(itemName.length() > maxAmountLength)
                maxAmountLength = itemName.length();
        }
        for(int i=0; i<itemNames.size(); i++)
        {

            content.append(" | ");
            content.append("_".repeat(Math.max(0, maxAmountLength - itemBalances.get(i).length() + 1)));
            content.append(itemBalances.get(i)).append(" ");

            content.append(" ").append(itemNames.get(i)).append("\n");


        }
        return content.toString();
    }
}
*/