package com.aalto.movesense.smartgloves4cpr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.movesense.mds.Mds;
import com.movesense.mds.MdsConnectionListener;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsSubscription;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.util.ArrayList;

import io.reactivex.disposables.Disposable;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemLongClickListener, AdapterView.OnItemClickListener  {

    // True if not connecting to a Suunto Movesense device.
    public static boolean TESTING = false;

    public static final String MOVESENSE_NAME = "Movesense";

    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static final int MY_PERMISSIONS_REQUEST_LOCATION = 1;

    // MDS
    protected static Mds mMds;

    // BleClient singleton
    static private RxBleClient mBleClient;

    private final ArrayList<MyScanResult> mScanResArrayList = new ArrayList<>();
    private ArrayAdapter<MyScanResult> mScanResArrayAdapter;
    private TextView connectTxt;

    // Sensor subscription
    private MdsSubscription mdsSubscription;
    private String subscribedDeviceSerial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //set up fullscreen and UI
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#ffffff"));
        connectTxt=(TextView)findViewById(R.id.ConnectTxt);


        // Init Scan UI
        // UI
        ListView mScanResultListView = (ListView) findViewById(R.id.listScanResult);
        mScanResArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, mScanResArrayList);
        mScanResultListView.setAdapter(mScanResArrayAdapter);
        mScanResultListView.setOnItemLongClickListener(this);
        mScanResultListView.setOnItemClickListener(this);


        // Make sure we have all the permissions this app needs
        requestNeededPermissions();

        // Initialize Movesense MDS library
        initMds();
    }

    private RxBleClient getBleClient() {
        // Init RxAndroidBle (Ble helper library) if not yet initialized
        if (mBleClient == null)
        {
            mBleClient = RxBleClient.create(this);
        }

        return mBleClient;
    }

    private void initMds() {
        mMds = Mds.builder().build(this);
    }

    void requestNeededPermissions()
    {
        // Here, thisActivity is the current activity
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                MY_PERMISSIONS_REQUEST_LOCATION);

        }

    }





    Disposable mScanSubscription;
    public void onScanClicked(View view) {
        findViewById(R.id.buttonScan).setVisibility(View.GONE);
        findViewById(R.id.buttonScanStop).setVisibility(View.VISIBLE);

        for (int i =0; i< mScanResArrayList.size();i++){
            MyScanResult device = mScanResArrayList.get(i);


                Log.i(LOG_TAG, "Disconnecting from BLE device: " + device.macAddress);
                mMds.disconnect(device.macAddress);

        }

        // Start with empty list
        mScanResArrayList.clear();
        mScanResArrayAdapter.notifyDataSetChanged();

        mScanSubscription = getBleClient().scanBleDevices(
                new ScanSettings.Builder()
                        // .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // change if needed
                        // .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // change if needed
                        .build()
                // add filters if needed
        )
                .subscribe(
                        scanResult -> {
                            Log.d(LOG_TAG,"scanResult: " + scanResult);

                            // Process scan result here.
                            if (scanResult.getBleDevice()!=null &&
                                    scanResult.getBleDevice().getName() != null) {

                                // replace if exists already, add otherwise
                                MyScanResult msr = new MyScanResult(scanResult);
                                if (mScanResArrayList.contains(msr)) {
                                    mScanResArrayList.set(mScanResArrayList.indexOf(msr), msr);
                                } else if(scanResult.getBleDevice().getName().startsWith(MOVESENSE_NAME)){
                                    mScanResArrayList.add(0, msr);
                                } else {
                                    mScanResArrayList.add(msr);
                                }

                                mScanResArrayAdapter.notifyDataSetChanged();
                            }
                        },
                        throwable -> {
                            Log.e(LOG_TAG,"scan error: " + throwable);
                            // Handle an error here.

                            // Re-enable scan buttons, just like with ScanStop
                            onScanStopClicked(null);
                        }
                );
    }




    public void onScanStopClicked(View view) {
        if (mScanSubscription != null)
        {
            mScanSubscription.dispose();
            mScanSubscription = null;
        }

        findViewById(R.id.buttonScan).setVisibility(View.VISIBLE);
        findViewById(R.id.buttonScanStop).setVisibility(View.GONE);
    }




    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return;

        MyScanResult device = mScanResArrayList.get(position);
        if (!device.isConnected()) {
            // Stop scanning
            onScanStopClicked(null);
            // And connect to the device
            //indicate connection
            TESTING = !device.name.startsWith(MOVESENSE_NAME);
            connectTxt.setVisibility(View.VISIBLE);
            connectBLEDevice(device);
        }
    }






    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return false;

        MyScanResult device = mScanResArrayList.get(position);

        // unsubscribe if there
        Log.d(LOG_TAG, "onItemLongClick, " + device.connectedSerial + " vs " + subscribedDeviceSerial);
        return device.isConnected();
    }

    private void connectBLEDevice(MyScanResult device) {

        RxBleDevice bleDevice = getBleClient().getBleDevice(device.macAddress);
        final Activity me = this;
        Log.i(LOG_TAG, "Connecting to BLE device: " + bleDevice.getMacAddress());
        if(TESTING) {
            connectTxt.setVisibility(View.INVISIBLE);
            Intent intent = new Intent(me, TrainingActivity.class);
            intent.putExtra(TrainingActivity.SERIAL, "none");
            startActivity(intent);
        } else {
            mMds.connect(bleDevice.getMacAddress(), new MdsConnectionListener() {

                @Override
                public void onConnect(String s) {
                    Log.d(LOG_TAG, "onConnect:" + s);
                }

                @Override
                public void onConnectionComplete(String macAddress, String serial) {
                    for (MyScanResult sr : mScanResArrayList) {
                        if (sr.macAddress.equalsIgnoreCase(macAddress)) {
                            sr.markConnected(serial);
                            break;
                        }
                    }
                    mScanResArrayAdapter.notifyDataSetChanged();
                    // Open the DataLoggerActivity
                    connectTxt.setVisibility(View.INVISIBLE);
                    Intent intent = new Intent(me, TrainingActivity.class);
                    intent.putExtra(TrainingActivity.SERIAL, serial);
                    startActivity(intent);
                }

                @Override
                public void onError(MdsException e) {
                    Log.e(LOG_TAG, "onError:" + e);
                    connectTxt.setVisibility(View.INVISIBLE);
                    showConnectionError(e);
                }

                @Override
                public void onDisconnect(String bleAddress) {

                    Log.d(LOG_TAG, "onDisconnect: " + bleAddress);
                    for (MyScanResult sr : mScanResArrayList) {
                        if (bleAddress.equals(sr.macAddress)) {
                            // unsubscribe if was subscribed
                            if (sr.connectedSerial != null && sr.connectedSerial.equals(subscribedDeviceSerial))
                                unsubscribe();

                            sr.markDisconnected();
                        }
                    }

                    mScanResArrayAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    private void showConnectionError(MdsException e) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Connection Error:")
                .setMessage(e.getMessage());

        builder.create().show();
    }

    private void unsubscribe() {
        if (mdsSubscription != null) {
            mdsSubscription.unsubscribe();
            mdsSubscription = null;
        }

        subscribedDeviceSerial = null;

        // If UI not invisible, do it now
        final View sensorUI = findViewById(R.id.sensorUI);
        if (sensorUI.getVisibility() != View.GONE)
            sensorUI.setVisibility(View.GONE);

    }



}
