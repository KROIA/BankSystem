package net.kroia.banksystem;

/*
public class BankSystemConfig {
    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public static final class Settings
    {
        public static final class Utilities
        {
            public static final class Logging
            {
                public boolean enableInfo = true;
                public boolean enableWarning = true;
                public boolean enableError = true;
                public boolean enableDebug = false;
            }
            public long saveInterval = 5L; // Minutes
            public Logging logging = new Logging();
        }
        public static final class Player
        {
            public long startingMoneyBalance = 0L;
        }
        public static final class Bank
        {
            public int itemTransferTickInterval = 2;
            public List<ItemID> items = new ArrayList<>();
        }


        public Utilities utilities = new Utilities();
        public Player player = new Player();
        public Bank bank = new Bank();
    }


    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Path configPath;
    private Settings settings;

    private BankSystemConfig(Path  configPath) {
        this.configPath = configPath;
    }
    
    public static BankSystemConfig create(Path configPath) {
        BankSystemConfig  bankSystemConfig = new BankSystemConfig(configPath);
        bankSystemConfig.load();
        return bankSystemConfig;
    }



    public Settings getSettings() {
        return settings;
    }



    public void load() {
        if (!Files.exists(configPath)) {
            settings = new Settings();
            save();
            info("Created default config at "+ configPath);
        }
        try (Reader reader = new FileReader(configPath.toFile())) {
            settings = GSON.fromJson(reader, Settings.class);
            if(settings == null)
                settings = new Settings();
        } catch (IOException e) {
            error("Failed to read config, using defaults", e);
            settings = new Settings();
        }
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = new FileWriter(configPath.toFile())) {
                GSON.toJson(settings, writer);
            }
        } catch (IOException e) {
            error("Failed to save default config", e);
        }
    }


    protected static void info(String message) {
        BACKEND_INSTANCES.LOGGER.info("[BankSystemConfig]"+message);
    }
    protected static void error(String message) {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemConfig]"+message);
    }
    protected static void error(String message, Throwable throwable) {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemConfig]"+message, throwable);
    }
    protected static void warn(String message) {
        BACKEND_INSTANCES.LOGGER.warn("[BankSystemConfig]"+message);
    }
    protected static void debug(String message) {
        BACKEND_INSTANCES.LOGGER.debug("[BankSystemConfig]"+message);
    }
}*/
