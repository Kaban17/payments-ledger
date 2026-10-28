package com.paymentsledger;

public final class Main {

    public static void main(String[] args) {
        Config config = Config.fromEnv();
        App app = App.create(config).start(config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
    }
}
