package ru.evgeniy.dpitunnel;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.gun0912.tedpermission.PermissionListener;
import com.gun0912.tedpermission.TedPermission;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.Permission;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class MainActivity extends AppCompatActivity {
    public static final int REQUEST_VPN = 1;

    private Button mainButton;
    private ImageButton settingsButton;
    private ImageButton browserButton;
    private Button updateHostlistButton;
    private TextView asciiLogo;
    private static boolean isOnBoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Set gefault settings values
        PreferenceManager.setDefaultValues(this, R.xml.settings, false);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (!prefs.getBoolean("firstTimeFlag", false)) {
            // do one time tasks
            updateHostlist();

            // mark first time has ran.
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("firstTimeFlag", true);
            editor.putString("hostlist_path", getFilesDir() + "/hostlist.txt");
            editor.commit();
        }

        // Check is activity started from BootReceiver
        isOnBoot = getIntent().getBooleanExtra("ON_BOOT", false);

        // Find layout elements
        mainButton = findViewById(R.id.main_button);
        settingsButton = findViewById(R.id.settings_button);
        browserButton = findViewById(R.id.browser_button);
        updateHostlistButton = findViewById(R.id.update_hostlist_button);
        asciiLogo = findViewById(R.id.ascii_logo);

        // Set logo state
        if(isServiceRunning(NativeService.class)) {
            asciiLogo.setText(R.string.app_ascii_logo_unlock);
            asciiLogo.setTextColor(getResources().getColor(R.color.colorAccent));
            mainButton.setText(R.string.on);
        }
        else {
            asciiLogo.setText(R.string.app_ascii_logo_lock);
            asciiLogo.setTextColor(getResources().getColor(R.color.textColor));
            mainButton.setText(R.string.off);
        }

        // Create broadcast receiver to update button and logo state on service run/stop
        IntentFilter updateState = new IntentFilter();
        updateState.addAction("LOGO_BUTTON_OFF");
        updateState.addAction("LOGO_BUTTON_ON");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals("LOGO_BUTTON_OFF")) {
                    // Stop VPN if need
                    if(prefs.getBoolean("other_vpn_setting", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        stopVpn();
                    }

                    // Make logo red and set button to off
                    asciiLogo.setText(R.string.app_ascii_logo_lock);
                    asciiLogo.setTextColor(Color.BLACK);
                    mainButton.setText(R.string.off);
                } else if (intent.getAction().equals("LOGO_BUTTON_ON")) {
                    // Start VPN if need
                    if(prefs.getBoolean("other_vpn_setting", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        startVpn();
                    }

                    // Make logo green and set button to on
                    asciiLogo.setText(R.string.app_ascii_logo_unlock);
                    asciiLogo.setTextColor(getResources().getColor(R.color.colorAccent));
                    mainButton.setText(R.string.on);

                    // Close activity if it started from BootReceiver
                    if(isOnBoot) {
                        MainActivity.isOnBoot = false;

                        Intent intent1 = new Intent(Intent.ACTION_MAIN);
                        intent1.addCategory(Intent.CATEGORY_HOME);
                        startActivity(intent1);
                    }
                }
            }
        }, updateState);

        // Initialize buttons
        mainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(isServiceRunning(NativeService.class)) {
                    stopService(new Intent(MainActivity.this, NativeService.class));
                }
                else {
                    // Check permissions
                    PermissionListener permissionListener = new PermissionListener() {
                        @Override
                        public void onPermissionGranted() {
                            // If ok start service
                            Intent intent = new Intent(MainActivity.this, NativeService.class);

                            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                startForegroundService(intent);
                            else
                                startService(intent);
                        }

                        @Override
                        public void onPermissionDenied(List<String> deniedPermissions) {
                            // If not ok show warning
                            Toast.makeText(MainActivity.this, getString(R.string.please_grant_permissions), Toast.LENGTH_LONG).show();
                        }
                    };

                    TedPermission.with(MainActivity.this)
                            .setPermissionListener(permissionListener)
                            .setPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
                            .check();
                }
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.this.startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        browserButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.this.startActivity(new Intent(MainActivity.this, BrowserActivity.class));
            }
        });
        updateHostlistButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateHostlist();
            }
        });

        // Automatically start on boot if need
        if(isOnBoot) {
            mainButton.performClick();
        }
    }

    private void stopVpn() {
        Tun2HttpVpnService.stop(this);
    }

    private void startVpn() {
        Intent i = VpnService.prepare(this);
        if (i != null) {
            startActivityForResult(i, REQUEST_VPN);
        } else {
            onActivityResult(REQUEST_VPN, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_VPN) {
            Tun2HttpVpnService.start(this);
        }
    }

    private void updateHostlist() {
        PermissionListener permissionListener = new PermissionListener() {
            @Override
            public void onPermissionGranted() {
                // If ok update hostlist
                new updateHostlistTask().execute();
            }

            @Override
            public void onPermissionDenied(List<String> deniedPermissions) {
                // If not ok show warning
                Toast.makeText(MainActivity.this, getString(R.string.please_grant_permissions), Toast.LENGTH_LONG).show();
            }
        };

        TedPermission.with(MainActivity.this)
                .setPermissionListener(permissionListener)
                .setPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .check();
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private class updateHostlistTask extends AsyncTask<Void, Void, Void> {
        private SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        private ProgressBar updateHostlistBar = findViewById(R.id.update_hostlist_bar);
        private boolean isOK = true;

        @Override
        protected void onPreExecute() {
            // Show ProgressBar
            updateHostlistBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected Void doInBackground(Void... values) {
            try {
                FileOutputStream f = new FileOutputStream(prefs.getString("hostlist_path", null));
                URL u = new URL(prefs.getString("hostlist_source", null));
                HttpsURLConnection c = (HttpsURLConnection) u.openConnection();

                // Create the SSL connection
                SSLContext sc;
                sc = SSLContext.getInstance("TLS");
                sc.init(null, null, new java.security.SecureRandom());
                c.setSSLSocketFactory(sc.getSocketFactory());

                // Set options and connect
                c.setReadTimeout(700);
                c.setConnectTimeout(700);
                c.setRequestMethod("GET");
                c.setDoInput(true);

                // Save to file
                InputStream in = c.getInputStream();

                byte[] buffer = new byte[1024];
                int len1 = 0;
                while ((len1 = in.read(buffer)) > 0) {
                    f.write(buffer, 0, len1);
                }

                f.close();
            } catch (Exception e) {
                e.printStackTrace();
                isOK = false;
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            // Hide ProgressBar
            updateHostlistBar.setVisibility(View.INVISIBLE);

            // Show updateHostlistStatus
            if(isOK)
            {
                Toast.makeText(MainActivity.this, getString(R.string.update_hostlist_ok), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, getString(R.string.update_hostlist_bad), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
