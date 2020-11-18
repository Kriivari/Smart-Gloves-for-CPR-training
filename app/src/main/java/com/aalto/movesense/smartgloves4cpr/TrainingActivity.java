package com.aalto.movesense.smartgloves4cpr;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;


import com.google.gson.Gson;
import com.movesense.mds.Mds;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsNotificationListener;
import com.movesense.mds.MdsResponseListener;
import com.movesense.mds.MdsSubscription;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class TrainingActivity extends AppCompatActivity {

    private static final int GOOD_SPEED_LOWER_LIMIT = 100;
    private static final int GOOD_SPEED_HIGHER_LIMIT = 120;
    private static final int GOOD_DEPTH_LOWER_LIMIT = 50;
    private static final int GOOD_DEPTH_HIGHER_LIMIT = 60;

    // UI colors
    private final int DEEP_OR_FAST_COLOR = Color.argb(255, 0, 158, 155);
    private final int SHALLOW_OR_SLOW_COLOR = Color.argb(255, 240, 228, 66);
    private final int GOOD_COMPRESSION_COLOR = Color.argb(255, 0, 114, 78);

    private static final String LOG_TAG = TrainingActivity.class.getSimpleName();
    public static final String SERIAL = "serial";
    String connectedSerial;

    private MdsSubscription mdsSubscription;
    private DataLoggerState mDLState;

    //URI:s
    private static final String URI_MDS_LOGBOOK_DATA = "suunto://MDS/Logbook/{0}/ById/{1}/Data";
    private static final String URI_DATALOGGER_STATE = "suunto://{0}/Mem/DataLogger/State";
    public static final String URI_EVENTLISTENER = "suunto://MDS/EventListener";
    private static final String URI_LOGBOOK_ENTRIES = "suunto://{0}/Mem/Logbook/Entries";
    private static final String URI_DATALOGGER_CONFIG = "suunto://{0}/Mem/DataLogger/Config";

    private static final double minTop = -7;
    private static final double minBot = -12;

    //Data variables setup
    private AccDataResponse[] dataArray = new AccDataResponse[1];
    private AccDataResponse[] twoSecs = new AccDataResponse[26];
    private double depth = 90;
    private double freq = 100;
    private Timer metronome;

    // UI elements
    private Button saveBtn;
    private ProgressBar freqProgressBar;
    private ProgressBar depthProgressBar;
    private TextView mSaveText;
    private TextView mFreqText;
    private TextView mDepthText;
    private ImageView mUpArrow;
    private ImageView mDownArrow;

    //Vuzix testing
    private final boolean TESTING = MainActivity.TESTING;

    private Mds getMDS() {
        return MainActivity.mMds;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training);

        // Find serial in opening intent
        Intent intent = getIntent();
        connectedSerial = intent.getStringExtra(SERIAL);

        //set up fullscreen and UI
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN + View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        decorView.setSystemUiVisibility(uiOptions);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#ffffff"));
        saveBtn = (Button) findViewById(R.id.save);
        saveBtn.setVisibility(View.GONE);
        mUpArrow = ((ImageView) findViewById(R.id.upArrow));
        mUpArrow.setVisibility(View.INVISIBLE);
        mDownArrow = ((ImageView) findViewById(R.id.downArrow));
        mDownArrow.setVisibility(View.INVISIBLE);

        configureDataLogger();
        fetchDataLoggerState();
        freqProgressBar = (ProgressBar) findViewById(R.id.freqBar);
        depthProgressBar = (ProgressBar) findViewById(R.id.depthBar);
        mFreqText = ((TextView) findViewById(R.id.freq));
        mDepthText = ((TextView) findViewById(R.id.depth));
        mSaveText = (TextView) findViewById(R.id.savetext);

        saveBtn.setOnClickListener(view -> {
            mSaveText.setVisibility(View.VISIBLE);
            fetchLogEntry();

        });

        playMetronome();

        if (TESTING) {
            //Vuzix testing
            Timer testTimer = new Timer();
            testTimer.schedule(new TimerTask() {
                public void run() {
                    updateDisplayTest();
                }
            }, 0, 1000);
        } else {
            eraseAllLogs();
            subscribeToSensor(connectedSerial);
            setDataLoggerState(true);
        }

    }

    //Vuzix testing
    private void updateDisplayTest() {
        this.runOnUiThread(tick);
    }

    private final Runnable tick = () -> {
        double freqRandom = Math.random();
        if (freqRandom < 0.2) {
            freq -= 1;
        } else if (freqRandom > 0.8) {
            freq += 1;
        }
        double depthRandom = Math.random();
        if (depthRandom < 0.2) {
            depth -= 5;
        } else if (depthRandom > 0.8) {
            depth += 5;
        }
        if (freq < 80) {
            freq = 80;
        }
        if (freq > 140) {
            freq = 140;
        }
        if (depth < 30) {
            depth = 30;
        }
        if (depth > 80) {
            depth = 80;
        }
        updateDisplay();
    };

    @Override
    public void onBackPressed() {
        AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle("Exiting training")
                .setMessage("Are you sure you want to exit and lose the data from this training?")
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    setDataLoggerState(false);
                    unsubscribe();
                    getMDS().disconnect(connectedSerial);
                    finish();
                })
                .setNegativeButton(android.R.string.no, (dialog, which) -> {
                    // do nothing
                }).show();
    }

    private void updateDisplay() {
        int freqInt = (int) freq;
        int depthInt = (int) depth;
        String freqStr = String.format(Locale.getDefault(), "%.0f", freq);
        String distStr = String.format(Locale.getDefault(), "%.0f", depth);
        mFreqText.setText(freqStr);
        mDepthText.setText(distStr);
        freqProgressBar.setProgress(freqInt);
        if (freqInt < GOOD_SPEED_LOWER_LIMIT) {
            freqProgressBar.setBackgroundColor(SHALLOW_OR_SLOW_COLOR);
        } else if (freqInt > GOOD_SPEED_HIGHER_LIMIT) {
            freqProgressBar.setBackgroundColor(DEEP_OR_FAST_COLOR);
        } else {
            freqProgressBar.setBackgroundColor(GOOD_COMPRESSION_COLOR);
        }
        depthProgressBar.setProgress(depthInt);
        if (depthInt < GOOD_DEPTH_LOWER_LIMIT) {
            depthProgressBar.setBackgroundColor(SHALLOW_OR_SLOW_COLOR);
            mUpArrow.setVisibility(View.INVISIBLE);
            mDownArrow.setVisibility(View.VISIBLE);
            mDownArrow.bringToFront();
        } else if (depthInt > GOOD_DEPTH_HIGHER_LIMIT) {
            depthProgressBar.setBackgroundColor(DEEP_OR_FAST_COLOR);
            mUpArrow.setVisibility(View.VISIBLE);
            mUpArrow.bringToFront();
            mDownArrow.setVisibility(View.INVISIBLE);
        } else {
            depthProgressBar.setBackgroundColor(GOOD_COMPRESSION_COLOR);
            mUpArrow.setVisibility(View.INVISIBLE);
            mDownArrow.setVisibility(View.INVISIBLE);
        }
    }

    private double singleDistanceStep(int i) {
        double accDiff = twoSecs[i].body.array[0].z - twoSecs[i - 1].body.array[0].z;
        double timeDiff = twoSecs[i].body.timestamp / 1000.0 - twoSecs[i - 1].body.timestamp / 1000.0;
        return 0.5 * (accDiff) * Math.pow(timeDiff, 2);
    }

    private void freqAndDepthCalc() {
        twoSecs = Arrays.copyOfRange(dataArray, dataArray.length - 26, dataArray.length - 1);
        freq = 0;
        depth = 0;
        ArrayList<Double> distances = new ArrayList<>();
        double distance = 0;
        double sum;
        int peaks = 0;
        for (int i = 0, high = 0, low = 0; i < twoSecs.length; i++) {
            if (high == 0 && low == 0) {
                if (twoSecs[i].body.array[0].z > minTop) {
                    high = 1;
                    if (i != 0) {
                        distance = singleDistanceStep(i);
                    }
                }
                if (twoSecs[i].body.array[0].z < minBot) {
                    low = 1;
                }

            } else {
                distance += singleDistanceStep(i);
                if (twoSecs[i].body.array[0].z > minTop && low == 1) {
                    low = 0;
                    peaks++;
                }
                if (twoSecs[i].body.array[0].z < minBot && high == 1) {
                    high = 0;
                    peaks++;
                    distances.add(distance);
                    sum = 0;

                    for (int p = 0; p < distances.size(); p++) {
                        sum = sum + distances.get(p);
                    }
                    depth = Math.abs(sum / (double) distances.size()) * 1000;

                    distance = 0;
                }
            }
        }
        freq = (peaks / 4.0) * 60.0;
    }

    private void subscribeToSensor(String connectedSerial) {
        // Clean up existing subscription (if there is one)
        if (mdsSubscription != null) {
            unsubscribe();
        }
        dataArray = Arrays.copyOf(dataArray, 1);
        // Build JSON doc that describes what resource and device to subscribe
        // Here we subscribe to 13 hertz accelerometer data
        // Sensor subscription
        //subscription with frequency of 13Hz
        String URI_MEAS_ACC_13 = "/Meas/Acc/13";
        String strContract = "{\"Uri\": \"" + connectedSerial + URI_MEAS_ACC_13 + "\"}";
        Log.d(LOG_TAG, strContract);

        mdsSubscription = Mds.builder().build(this).subscribe(URI_EVENTLISTENER,
                strContract, new MdsNotificationListener() {
                    @Override
                    public void onNotification(String data) {
                        Log.d(LOG_TAG, "onNotification(): " + data);
                        // If UI not enabled, do it now

                        AccDataResponse accResponse = new Gson().fromJson(data, AccDataResponse.class);
                        if (accResponse != null && accResponse.body.array.length > 0) {
                            dataArray = Arrays.copyOf(dataArray, dataArray.length + 1);
                            dataArray[dataArray.length - 1] = accResponse;

                        }
                        if (accResponse != null && dataArray.length > 26 && dataArray.length % 2 == 0) {
                            freqAndDepthCalc();
                            updateDisplay();
                        }

                    }

                    @Override
                    public void onError(MdsException error) {
                        Log.e(LOG_TAG, "subscription onError(): ", error);
                        unsubscribe();
                    }
                });

    }

    private void setDataLoggerState(boolean bStartLogging) {
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_STATE, connectedSerial);

        int newState = bStartLogging ? 3 : 2;
        String payload = "{\"newState\":" + newState + "}";
        getMDS().put(stateUri, payload, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "PUT state succesful: " + data);

                mDLState.content = newState;
                // Update log list if we stopped

            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "PUT DataLogger/State returned error: " + e);
            }
        });
    }

    private void fetchDataLoggerState() {
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_STATE, connectedSerial);

        getMDS().get(stateUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "GET state succesful: " + data);

                mDLState = new Gson().fromJson(data, DataLoggerState.class);

            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "GET DataLogger/State returned error: " + e);
            }
        });
    }

    private void saveLogToFile(String filename, String data) {
        // Get the directory for the user's public pictures directory.
        final File path =
                Environment.getExternalStoragePublicDirectory
                        (
                                Environment.DIRECTORY_DOWNLOADS + "/MovesenseLogs/"
                        );

        // Make sure the path directory exists.
        if (!path.exists()) {
            // Make it, if it doesn't exit
            path.mkdirs();
        }

        final File file = new File(path, filename + ".json");

        // Save data to the file
        Log.d(LOG_TAG, "Writing data to file: " + file.getAbsolutePath());

        try {
            FileOutputStream fOut = new FileOutputStream(file.getAbsolutePath(), false);
            OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);

            // Write in pieces in case the file is big
            final int BLOCK_SIZE = 4096;
            for (int startIdx = 0; startIdx < data.length(); startIdx += BLOCK_SIZE) {
                int endIdx = Math.min(data.length(), startIdx + BLOCK_SIZE);
                myOutWriter.write(data.substring(startIdx, endIdx));
            }

            myOutWriter.flush();
            myOutWriter.close();

            fOut.flush();
            fOut.close();
            mSaveText.setText("File saved to " + file.getAbsolutePath());
            saveBtn.setVisibility(View.INVISIBLE);
        } catch (IOException e) {
            Log.e(LOG_TAG, "File write failed: ", e);
            mSaveText.setText("Saving failed: check permissions");
            saveBtn.setVisibility(View.INVISIBLE);
        }

        // re-scan files so that they get visible in Windows
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
    }

    private void configureDataLogger() {
        // Access the DataLogger/Config
        String configUri = MessageFormat.format(URI_DATALOGGER_CONFIG, connectedSerial);

        // Create the config object
        DataLoggerConfig.DataEntry[] entries = {new DataLoggerConfig.DataEntry("/Meas/Acc/13")};
        DataLoggerConfig config = new DataLoggerConfig(new DataLoggerConfig.Config(new DataLoggerConfig.DataEntries(entries)));
        String jsonConfig = new Gson().toJson(config, DataLoggerConfig.class);

        Log.d(LOG_TAG, "Config request: " + jsonConfig);
        getMDS().put(configUri, jsonConfig, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "PUT config succesful: " + data);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "PUT DataLogger/Config returned error: " + e);
            }
        });
    }

    private void eraseAllLogs() {
        // Access the Logbook/Entries resource
        String entriesUri = MessageFormat.format(URI_LOGBOOK_ENTRIES, connectedSerial);


        getMDS().delete(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "DELETE LogEntries succesful: " + data);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "DELETE LogEntries returned error: " + e);
            }
        });
    }

    private void fetchLogEntry() {
        // GET the /MDS/Logbook/Data proxy
        String logDataUri = MessageFormat.format(URI_MDS_LOGBOOK_DATA, connectedSerial, 1);
        Long tsLong = System.currentTimeMillis() / 1000;
        String ts = tsLong.toString();
        getMDS().get(logDataUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(final String data) {
                String loggableData = data.substring(0, Math.min(8192, data.length()));
                Log.i(LOG_TAG, "GET Log Data succesful: " + loggableData);
                saveLogToFile("SmartGloves4CPR" + ts, data);

            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "GET Log Data returned error: " + e);
            }
        });
    }


    private void unsubscribe() {
        if (mdsSubscription != null) {
            mdsSubscription.unsubscribe();
            mdsSubscription = null;
        }
        if(metronome != null ) {
            metronome.cancel();;
        }
    }

    public void playMetronome() {
        metronome = new Timer();
        metronome.schedule(new TimerTask() {
            public void run() {
                playSound();
            }
        }, 0, 570); // 570 = 60000 / 105
    }

    private void playSound() {
        this.runOnUiThread(() -> {
            if (freq > GOOD_SPEED_HIGHER_LIMIT || freq < GOOD_SPEED_LOWER_LIMIT) {
                getWindow().getDecorView().playSoundEffect(SoundEffectConstants.CLICK);
            }
        });
    }

}