package net.kroia.banksystem.banking;

public enum BankPermission
{
    DEPOSIT(1),
    WITHDRAW(1 << 1),
    MANAGE(1 << 2);

    private final int value;

    BankPermission(int i) {
        this.value = i;
    }
    public int getValue() {
        return value;
    }
    public static boolean hasPermission(int permissions, BankPermission permission) {
        return (permissions & permission.getValue()) != 0;
    }
    public static boolean hasPermission(int permissions, int permission) {
        return (permissions & permission) == permission;
    }
    public static boolean hasAnyPermission(int permissions, int permission) {
        return (permissions & permission) > 0;
    }
    public static int addPermission(int permissions, BankPermission permission) {
        return permissions | permission.getValue();
    }
    public static int removePermission(int permissions, BankPermission permission) {
        return permissions & ~permission.getValue();
    }
    public static int togglePermission(int permissions, BankPermission permission) {
        return permissions ^ permission.getValue();
    }
    public static int clearPermissions(int permissions) {
        return 0;
    }
    public static int setPermissions(int permissions, BankPermission... permissionsToSet) {
        for (BankPermission perm : permissionsToSet) {
            permissions = addPermission(permissions, perm);
        }
        return permissions;
    }
    public static int removePermissions(int permissions, BankPermission... permissionsToRemove) {
        for (BankPermission perm : permissionsToRemove) {
            permissions = removePermission(permissions, perm);
        }
        return permissions;
    }
    public static int togglePermissions(int permissions, BankPermission... permissionsToToggle) {
        for (BankPermission perm : permissionsToToggle) {
            permissions = togglePermission(permissions, perm);
        }
        return permissions;
    }

    public static int getSelfOwnerPermissions()
    {
        return DEPOSIT.getValue() | WITHDRAW.getValue() | MANAGE.getValue();
    }
    public static int getAllPermissions()
    {
        return getSelfOwnerPermissions();
    }
    public static String toString(int permissions)
    {
        StringBuilder sb = new StringBuilder();
        if (hasPermission(permissions, DEPOSIT)) {
            sb.append("DEPOSIT ");
        }
        if (hasPermission(permissions, WITHDRAW)) {
            sb.append("WITHDRAW ");
        }
        if (hasPermission(permissions, MANAGE)) {
            sb.append("MANAGE ");
        }
        return sb.toString().trim();
    }

}