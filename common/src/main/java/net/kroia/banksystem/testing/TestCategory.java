package net.kroia.banksystem.testing;

public enum TestCategory {

    PERMISSION("permission", "Bank permission logic tests", ServerType.BOTH, false),
    BANK_ACCOUNT("bank_account", "Bank account creation and management tests", ServerType.MASTER_ONLY, false),
    BANK_MANAGER("bank_manager", "Bank manager operations tests", ServerType.MASTER_ONLY, false),
    MONEY("money", "Money transfer and balance tests", ServerType.MASTER_ONLY, false),
    ITEM_BANK("item_bank", "Item banking deposit/withdraw tests", ServerType.MASTER_ONLY, false),
    NETWORKING("networking", "Multi-server networking tests", ServerType.BOTH, true),
    COMMAND("command", "Command handler tests", ServerType.BOTH, true),
    DATA_PERSISTENCE("data_persistence", "Save/load data persistence tests", ServerType.MASTER_ONLY, true);

    public enum ServerType {
        MASTER_ONLY,
        SLAVE_ONLY,
        BOTH
    }

    private final String name;
    private final String description;
    private final ServerType serverType;
    private final boolean needsMinecraftContext;

    TestCategory(String name, String description, ServerType serverType, boolean needsMinecraftContext) {
        this.name = name;
        this.description = description;
        this.serverType = serverType;
        this.needsMinecraftContext = needsMinecraftContext;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ServerType getServerType() {
        return serverType;
    }

    public boolean needsMinecraftContext() {
        return needsMinecraftContext;
    }

    /**
     * Check if this category can run on the current server type.
     * @param isSlave true if the current server is a slave
     * @return true if the category is allowed to run
     */
    public boolean canRunOn(boolean isSlave) {
        if (serverType == ServerType.BOTH) {
            return true;
        }
        if (isSlave) {
            return serverType == ServerType.SLAVE_ONLY;
        }
        return serverType == ServerType.MASTER_ONLY;
    }

    /**
     * Find a TestCategory by its name string.
     * @param name the category name
     * @return the matching TestCategory, or null if not found
     */
    public static TestCategory fromName(String name) {
        for (TestCategory category : values()) {
            if (category.name.equalsIgnoreCase(name)) {
                return category;
            }
        }
        return null;
    }
}
