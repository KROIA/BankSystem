package net.kroia.banksystem.compat;

import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public class OldBankDataLoader {


    private final ServerBankManager manager;
    public OldBankDataLoader(ServerBankManager serverBankManager)
    {
        this.manager = serverBankManager;
    }


    public boolean load(CompoundTag tag)
    {
        boolean success = true;
        ListTag bankElements = tag.getList("users", 10);
        //userMap.clear();
        for (int i = 0; i < bankElements.size(); i++) {
            CompoundTag bankTag = bankElements.getCompound(i);
            //BankUserOld user = BankUserOld.loadFromTag(bankTag);
           /* if(user == null)
            {
                success = false;
                continue;
            }*/
            //userMap.put(user.getPlayerUUID(), user);
        }
        return success;
    }
}
