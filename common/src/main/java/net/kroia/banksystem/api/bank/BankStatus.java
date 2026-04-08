package net.kroia.banksystem.api.bank;

public enum BankStatus {
    SUCCESS,
    FAILED_NOT_ENOUGH_FUNDS,
    FAILED_OVERFLOW,
    FAILED_NEGATIVE_VALUE,
    FAILED_WRONG_INSTANCE_TYPE,
    FAILED_INVALID_ITEM_ID,
    FAILED_NO_BANK
}
