package net.kroia.banksystem.networking.packet.client_sender.update;

/*
public class UpdateBankAccountPacket extends BankSystemNetworkPacket {

    public static class BankData{
        public ItemID itemID;
        public long balance = 0;
        public boolean setBalance = false;
        public boolean resetLockedBalance = false;
        public boolean removeBank = false;
        public boolean createBank = false;


        public BankData(){}
        public BankData(FriendlyByteBuf buf) {
            fromBytes(buf);
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeItem(itemID.getStack());
            buf.writeLong(balance);
            buf.writeBoolean(setBalance);
            buf.writeBoolean(resetLockedBalance);
            buf.writeBoolean(removeBank);
            buf.writeBoolean(createBank);
        }


        public void fromBytes(FriendlyByteBuf buf) {
            itemID = new ItemID(buf.readItem());
            balance = buf.readLong();
            setBalance = buf.readBoolean();
            resetLockedBalance = buf.readBoolean();
            removeBank = buf.readBoolean();
            createBank = buf.readBoolean();
        }
    }
    int accountNumber;
    List<BankData> bankData;

    Map<UUID, Integer> setUsers;

    public UpdateBankAccountPacket(int accountNumber, List<BankData> bankData,
                                   Map<UUID, Integer> setUsers) {
        super();
        this.accountNumber = accountNumber;
        this.bankData = bankData;

        this.setUsers = setUsers;

    }

    public UpdateBankAccountPacket(int accountNumber,
                                   Map<UUID, Integer> setUsers) {
        super();
        this.accountNumber = accountNumber;
        this.bankData = null;

        this.setUsers = setUsers;


    }
    public UpdateBankAccountPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(int accountNumber, List<BankData> bankData,
                                  Map<UUID, Integer> setUsers) {
        UpdateBankAccountPacket packet = new UpdateBankAccountPacket(accountNumber, bankData, setUsers);
        packet.sendToServer();
    }


    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(accountNumber);
        buf.writeBoolean(bankData != null);
        if(bankData != null) {
            buf.writeInt(bankData.size());
            for (BankData data : bankData) {
                data.toBytes(buf);
            }
        }

        buf.writeBoolean(setUsers != null);
        if(setUsers != null) {
            buf.writeInt(setUsers.size());
            for (Map.Entry<UUID, Integer> entry : setUsers.entrySet()) {
                buf.writeUUID(entry.getKey());
                buf.writeInt(entry.getValue());
            }
        }
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        accountNumber = buf.readInt();

        bankData = null;
        if(buf.readBoolean()) {
            int size = buf.readInt();
            bankData = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                bankData.add(new BankData(buf));
            }
        }

        setUsers = null;
        if(buf.readBoolean()) {
            int addUsersSize = buf.readInt();
            setUsers = new java.util.HashMap<>(addUsersSize);
            for (int i = 0; i < addUsersSize; i++) {
                UUID userUUID = buf.readUUID();
                int permissions = buf.readInt();
                setUsers.put(userUUID, permissions);
            }
        }
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        // Check if the player is a admin
        boolean isAdmin = sender.hasPermissions(BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ADMIN_PERMISSION_LEVEL.get());

        BankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(accountNumber);
        if(account == null) {
            // If the account does not exist, we cannot update it
            return;
        }
        boolean canManage = account.hasPermission(sender.getUUID(), BankPermission.MANAGE.getValue());
        if (!isAdmin && !canManage) {
            return;
        }

        if(isAdmin && bankData != null) {
            for (BankData data : bankData) {
                if (data.removeBank) {
                    account.removeBank(data.itemID);
                    continue;
                }
                IBank bank = account.getBank(data.itemID);
                if (bank != null) {
                    if (data.resetLockedBalance)
                        bank.unlockAll();
                    if (data.setBalance)
                        bank.setBalance(data.balance);
                } else {
                    if (data.createBank) {
                        account.createBank(data.itemID, data.balance);
                    }
                }
            }
        }
        if(setUsers != null && !setUsers.isEmpty()) {
            Map<User, Integer> userList = new HashMap<>(setUsers.size());
            for (Map.Entry<UUID, Integer> entry : setUsers.entrySet()) {
                UUID userUUID = entry.getKey();
                int permissions = entry.getValue();
                User userToSet = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByUUID(userUUID);
                if(userToSet != null)
                {
                    userList.put(userToSet, permissions);
                }
            }
            account.setUsers(userList);
        }
    }
}*/
