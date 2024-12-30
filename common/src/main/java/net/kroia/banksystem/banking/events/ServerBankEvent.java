package net.kroia.banksystem.banking.events;

public class ServerBankEvent {

    protected final String msg;
    ServerBankEvent(String msg)
    {
        this.msg = msg;
    }
    public String getMsg()
    {
        return msg;
    }
}
