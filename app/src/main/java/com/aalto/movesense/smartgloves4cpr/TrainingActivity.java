package com.aalto.movesense.smartgloves4cpr;


import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.CountDownTimer;
import android.os.Environment;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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

public class TrainingActivity extends AppCompatActivity {

    private static final String LOG_TAG = TrainingActivity.class.getSimpleName();
    public static final String SERIAL = "serial";
    String connectedSerial;



    // Sensor subscription
    static private String URI_MEAS_ACC_13 = "/Meas/Acc/13"; //subscription with frequency of 13Hz
    private MdsSubscription mdsSubscription;
    private String subscribedDeviceSerial;
    private DataLoggerState mDLState;

    //URI:s
    public static final String URI_CONNECTEDDEVICES = "suunto://MDS/ConnectedDevices";
    private static final String URI_MDS_LOGBOOK_DATA= "suunto://MDS/Logbook/{0}/ById/{1}/Data";
    private static final String URI_DATALOGGER_STATE = "suunto://{0}/Mem/DataLogger/State";
    public static final String URI_EVENTLISTENER = "suunto://MDS/EventListener";
    public static final String SCHEME_PREFIX = "suunto://";
    private static final String URI_LOGBOOK_ENTRIES = "suunto://{0}/Mem/Logbook/Entries";
    private static final String URI_DATALOGGER_CONFIG = "suunto://{0}/Mem/DataLogger/Config";

    //Data variables setup
    private int counter;
    private AccDataResponse[] dataArray= new AccDataResponse[1];
    private AccDataResponse[] twoSecs= new AccDataResponse[26];
    private double minTop= -7;
    private double minBot= -12;
    private double depth;
    private double freq;

    // UI elements
    private Button saveBtn;
    private ProgressBar freqProgressBar;
    private ProgressBar depthProgressBar;
    private TextView mTextViewCountDown;
    private TextView mSaveText;
    private Button mButtonStart;

    //Timer setup
    private static final long START_TIME_IN_MILLIS = 60000;
    private CountDownTimer mCountDownTimer;
    private boolean mTimerRunning;
    private long mTimeLeftInMillis = START_TIME_IN_MILLIS;

    private Mds getMDS() {return MainActivity.mMds;}


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training);

        // Find serial in opening intent
        Intent intent = getIntent();
        connectedSerial = intent.getStringExtra(SERIAL);

        //set up fullscreen and UI
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#ccffff"));
        saveBtn=(Button) findViewById(R.id.save);
        saveBtn.setVisibility(View.GONE);
        mTextViewCountDown = (TextView) findViewById(R.id.timeText);
        (findViewById(R.id.countText)).setVisibility(View.GONE);
        mButtonStart = (Button)findViewById(R.id.startTraining);
        configureDataLogger();
        fetchDataLoggerState();
        freqProgressBar= (ProgressBar) findViewById(R.id.freqBar);
        depthProgressBar= (ProgressBar) findViewById(R.id.depthBar);
        mSaveText=(TextView)findViewById(R.id.savetext);

        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mButtonStart.setVisibility(View.GONE);
                mSaveText.setVisibility(View.VISIBLE);
                fetchLogEntry(1);

            }
        });
        mButtonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mTimerRunning) {
                    mTimeLeftInMillis=START_TIME_IN_MILLIS;
                    eraseAllLogs();
                    startTimer();
                    mButtonStart.setVisibility(View.INVISIBLE);
                    mSaveText.setVisibility(View.GONE);
                    subscribeToSensor(connectedSerial);
                    setDataLoggerState(true);

                }
            }
        });


    }

    private void startTimer() {
        mCountDownTimer = new CountDownTimer(mTimeLeftInMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                mTimeLeftInMillis = millisUntilFinished;
                updateCountDownText();
            }
            @Override
            public void onFinish() {
                mTimerRunning = false;
                mButtonStart.setText("Start");
                mButtonStart.setVisibility(View.INVISIBLE);
                setDataLoggerState(false);
                saveBtn.setVisibility(View.VISIBLE);
                unsubscribe();
                mButtonStart.setVisibility(View.VISIBLE);



            }
        }.start();
        mTimerRunning = true;
    }


    private void updateCountDownText() {
        int minutes = (int) (mTimeLeftInMillis / 1000) / 60;
        int seconds = (int) (mTimeLeftInMillis / 1000) % 60;
        String timeLeftFormatted = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        mTextViewCountDown.setText(timeLeftFormatted);
    }


    @Override
    public void onBackPressed() {
        AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle("Exiting training")
                .setMessage("Are you sure you want to exit and lose the data from this training?")
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        setDataLoggerState(false);
                        unsubscribe();
                        getMDS().disconnect(connectedSerial);
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // do nothing
                    }
                }).show();
    }

    private double singleDistanceStep(int i){
        double accDiff=0;
        double timeDiff=0;
        accDiff=twoSecs[i].body.array[0].z -twoSecs[i-1].body.array[0].z;
        timeDiff=twoSecs[i].body.timestamp/1000.0 -twoSecs[i-1].body.timestamp/1000.0;
        double distance=0.5 *(accDiff) * Math.pow(timeDiff,2);
        return distance;
    }
    private void freqAndDepthCalc(){
        twoSecs = Arrays.copyOfRange(dataArray,dataArray.length-26, dataArray.length-1 );
        freq=0;
        depth=0;
        ArrayList<Double> distances = new ArrayList<Double>();
        double distance=0;
        double sum=0;
        int peaks=0;
        for(int i=0,high=0,low=0; i<twoSecs.length; i++){
            if(high==0 && low==0){
                if(twoSecs[i].body.array[0].z> minTop){
                    high=1;
                    if(i!=0){
                        distance=singleDistanceStep(i);
                    }
                }
                if(twoSecs[i].body.array[0].z< minBot){
                    low=1;
                }

            }

            else{
                distance+=singleDistanceStep(i);
                if(twoSecs[i].body.array[0].z > minTop && low==1 ){
                    low=0;
                    peaks++;
                }
                if(twoSecs[i].body.array[0].z < minBot && high==1 ){
                    high=0;
                    peaks++;
                    distances.add(distance);
                    sum=0;

                    for(int p=0; p<distances.size();p++){
                        sum=sum + distances.get(p);
                    }
                    depth=Math.abs(sum/(double) distances.size())*100;

                    distance=0;
                }
            }
        }
        freq= (peaks / 4.0 ) * 60.0;
    }
    private void subscribeToSensor(String connectedSerial) {
        counter=0;
        // Clean up existing subscription (if there is one)
        if (mdsSubscription != null) {
            unsubscribe();
        }
        dataArray= Arrays.copyOf(dataArray,1);
        // Build JSON doc that describes what resource and device to subscribe
        // Here we subscribe to 13 hertz accelerometer data
        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"").append(connectedSerial).append(URI_MEAS_ACC_13).append("\"}").toString();
        Log.d(LOG_TAG, strContract);
        final View sensorUI = findViewById(R.id.sensorUI);

        subscribedDeviceSerial = connectedSerial;

        mdsSubscription = Mds.builder().build(this).subscribe(URI_EVENTLISTENER,
                strContract, new MdsNotificationListener() {
                    @Override
                    public void onNotification(String data) {
                        Log.d(LOG_TAG, "onNotification(): " + data);
                        // If UI not enabled, do it now

                        AccDataResponse accResponse = new Gson().fromJson(data, AccDataResponse.class);
                        if (accResponse != null && accResponse.body.array.length > 0) {
                            dataArray=Arrays.copyOf(dataArray,dataArray.length+1);
                            dataArray[dataArray.length-1]=accResponse;

                        }
                        if (accResponse != null && dataArray.length > 26 && dataArray.length % 2 == 0) {
                            freqAndDepthCalc();
                            int freqInt= (int) freq;
                            int depthInt= (int) depth;
                            String freqStr = String.format("%.0f",freq);
                            String distStr = String.format("%.0f",depth);
                            ((TextView) findViewById(R.id.freq)).setText(freqStr);
                            ((TextView) findViewById(R.id.depth)).setText(distStr);
                            freqProgressBar.setProgress(freqInt);
                            depthProgressBar.setProgress(depthInt);


                        }

                    }

                    @Override
                    public void onError(MdsException error) {
                        Log.e(LOG_TAG, "subscription onError(): ", error);
                        unsubscribe();
                    }
                });

    }

    private void createNewLog() {
        // Access the Logbook/Entries resource
        String entriesUri = MessageFormat.format(URI_LOGBOOK_ENTRIES, connectedSerial);

        getMDS().post(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "POST LogEntries succesful: " + data);
                IntResponse logIdResp = new Gson().fromJson(data, IntResponse.class);

            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "POST LogEntries returned error: " + e);
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
        if(!path.exists())
        {
            // Make it, if it doesn't exit
            path.mkdirs();
        }

        final File file = new File(path, filename + ".json");

        // Save data to the file
        Log.d(LOG_TAG, "Writing data to file: " + file.getAbsolutePath());

        try
        {
            FileOutputStream fOut = new FileOutputStream(file.getAbsolutePath(), false);
            OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);

            // Write in pieces in case the file is big
            final int BLOCK_SIZE= 4096;
            for (int startIdx=0;startIdx<data.length();startIdx+=BLOCK_SIZE) {
                int endIdx = Math.min(data.length(), startIdx + BLOCK_SIZE);
                myOutWriter.write(data.substring(startIdx, endIdx));
            }

            myOutWriter.flush();
            myOutWriter.close();

            fOut.flush();
            fOut.close();
            mSaveText.setText("File saved to "+file.getAbsolutePath());
            mButtonStart.setVisibility(View.VISIBLE);
            saveBtn.setVisibility(View.INVISIBLE);
        }
        catch (IOException e)
        {
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
        String jsonConfig = new Gson().toJson(config,DataLoggerConfig.class);

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
    private void fetchLogEntry(final int id) {
        // GET the /MDS/Logbook/Data proxy
        String logDataUri = MessageFormat.format(URI_MDS_LOGBOOK_DATA, connectedSerial, id);
        Long tsLong = System.currentTimeMillis()/1000;
        String ts = tsLong.toString();
        final Context me = this;
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

        subscribedDeviceSerial = null;

    }

}