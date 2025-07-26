package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.networking.arrs.GenericRequest;

public abstract class BankSystemGenericRequest<IN, OUT> extends GenericRequest<IN, OUT> {

    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

}
