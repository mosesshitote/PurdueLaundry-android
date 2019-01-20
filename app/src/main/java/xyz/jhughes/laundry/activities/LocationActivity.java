package xyz.jhughes.laundry.activities;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.util.Log;
import android.view.Menu;
import android.view.View;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import xyz.jhughes.laundry.BuildConfig;
import xyz.jhughes.laundry.LaundryParser.Location;
import xyz.jhughes.laundry.LaundryParser.Locations;
import xyz.jhughes.laundry.LaundryParser.MachineList;
import xyz.jhughes.laundry.LaundryParser.Rooms;
import xyz.jhughes.laundry.ModelOperations;
import xyz.jhughes.laundry.R;
import xyz.jhughes.laundry.adapters.LocationAdapter;
import xyz.jhughes.laundry.analytics.AnalyticsHelper;
import xyz.jhughes.laundry.analytics.ScreenTrackedActivity;
import xyz.jhughes.laundry.apiclient.MachineService;
import xyz.jhughes.laundry.databinding.ActivityLocationBinding;
import xyz.jhughes.laundry.storage.SharedPrefsHelper;

/**
 * The main activity of the app. Lists the locations of
 * laundry and an overview of the availabilities.
 */
public class LocationActivity extends ScreenTrackedActivity implements SwipeRefreshLayout.OnRefreshListener, View.OnClickListener {

    private ActivityLocationBinding binding;
    private LocationAdapter adapter;

    private boolean error = false;

    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences.Editor e = SharedPrefsHelper.getSharedPrefs(this).edit();
        e.putString("lastScreenViewed", "LocationList");
        e.apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding =  DataBindingUtil.setContentView(this, R.layout.activity_location);

        String msg;
        if ((msg = getIntent().getStringExtra("error")) != null) {
            showErrorMessage(msg);
        } else if (!isNetworkAvailable()) {
            showNoInternetDialog();
        } else if (!getIntent().getBooleanExtra("forceMainMenu", false)) {
            String lastRoom = SharedPrefsHelper.getSharedPrefs(this)
                    .getString("lastScreenViewed", null);
            if (lastRoom != null && !lastRoom.equals("LocationList")) {
                getRoomsCall(true, lastRoom);
            }
        }

        setScreenName("Location List");

        initRecyclerView();
        initToolbar();

        binding.locationListPuller.setOnRefreshListener(this);
        binding.locationErrorButton.setOnClickListener(this);
    }

    private void initToolbar() {
        setSupportActionBar(binding.toolbar);
    }

    private void initRecyclerView() {
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.recyclerView.setLayoutManager(layoutManager);
    }

    @Override
    protected void onStart() {
        super.onStart();
        binding.recyclerView.setAdapter(null);
        //We only want to clear the adapter/show the loading
        // if there are no items in the list already.
        if (binding.recyclerView.getAdapter() == null || binding.recyclerView.getAdapter().getItemCount() <= 0) {
            binding.recyclerView.setAdapter(null);
        }
        if (!error) {
            getRoomsCall(false, null);
            binding.progressBar.setVisibility(View.VISIBLE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        CharSequence name = getString(R.string.notification_channel_name);
        String description = getString(R.string.notification_channel_desc);
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel mChannel = new NotificationChannel(getString(R.string.notification_channel_name), name, importance);
        mChannel.setDescription(description);
        mChannel.enableVibration(true);
        mChannel.setSound(Uri.EMPTY, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        mChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
        mNotificationManager.createNotificationChannel(mChannel);
    }

    protected void getLaundryCall() {

        Call<Map<String, MachineList>> allMachineCall = BuildConfig.DEBUG ?
                MachineService.getService().getAllMachines_DEBUG() :
                MachineService.getService().getAllMachines();
        allMachineCall.enqueue(new Callback<Map<String, MachineList>>() {
            @Override
            public void onResponse(Call<Map<String, MachineList>> call, Response<Map<String, MachineList>> response) {
                if (response.isSuccessful()) {
                    Map<String, MachineList> machineMap = response.body();
                    List<Location> locations = ModelOperations.machineMapToLocationList(machineMap);
                    adapter = new LocationAdapter(locations, LocationActivity.this.getApplicationContext());

                    //We conditionally make the progress bar visible,
                    // but its cheap to always dismiss it without checking
                    // if its already gone.
                    binding.progressBar.setVisibility(View.GONE);
                    binding.recyclerView.setHasFixedSize(true);
                    binding.recyclerView.setAdapter(adapter);
                    binding.locationListPuller.setRefreshing(false);
                } else {
                    int httpCode = response.code();
                    if (httpCode < 500) {
                        //client error
                        showErrorMessage(getString(R.string.error_client_message));
                        AnalyticsHelper.sendEventHit("api", "apiCodes", "/location/all", httpCode);
                    } else {
                        //server error
                        showErrorMessage(getString(R.string.error_server_message));
                        AnalyticsHelper.sendEventHit("api", "apiCodes", "/location/all", httpCode);
                    }

                }
            }

            @Override
            public void onFailure(Call<Map<String, MachineList>> call, Throwable t) {
                Log.e("LocationActivity", "API ERROR - " + t.getMessage());
                //likely a timeout -- network is available due to prev. check
                showErrorMessage(getString(R.string.error_server_message));

                AnalyticsHelper.sendErrorHit(t, false);

                binding.locationListPuller.setRefreshing(false);
            }
        });
    }


    protected void getRoomsCall(final boolean goingToMachineActivity, final String lastRoom) {

        if (!isNetworkAvailable()) {
            binding.locationListPuller.setRefreshing(false);
            if (error) showErrorMessage("You have no internet connection.");
            else showNoInternetDialog();
            return;
        }
        hideErrorMessage();
        if (Rooms.getRoomsConstantsInstance().getListOfRooms() == null) {
            Call<List<Locations>> roomCall = BuildConfig.DEBUG ?
                    MachineService.getService().getLocations_DEBUG() :
                    MachineService.getService().getLocations();
            roomCall.enqueue(new Callback<List<Locations>>() {
                @Override
                public void onResponse(Call<List<Locations>> call, Response<List<Locations>> response) {
                    if (response.isSuccessful()) {
                        //set rooms
                        List<Locations> roomList = response.body();
                        String[] rooms = new String[roomList.size()];
                        for (int i = 0; i < roomList.size(); i++) {
                            rooms[i] = roomList.get(i).name;
                        }
                        Rooms.getRoomsConstantsInstance().setListOfRooms(rooms);
                        if (!goingToMachineActivity) {
                            //call laundry
                            getLaundryCall();
                        } else {
                            Intent intent = new Intent(LocationActivity.this, MachineActivity.class);
                            Bundle b = new Bundle();
                            b.putString("locationName", lastRoom);
                            intent.putExtras(b);
                            startActivity(intent);
                        }
                    } else {
                        int httpCode = response.code();
                        if (httpCode < 500) {
                            //client error
                            showErrorMessage(getString(R.string.error_client_message));
                            AnalyticsHelper.sendEventHit("api", "apiCodes", "/location/all", httpCode);
                        } else {
                            //server error
                            showErrorMessage(getString(R.string.error_server_message));
                            AnalyticsHelper.sendEventHit("api", "apiCodes", "/location/all", httpCode);
                        }

                    }
                }

                @Override
                public void onFailure(Call<List<Locations>> call, Throwable t) {
                    Log.e("LocationActivity", "API ERROR - " + t.getMessage());
                    //likely a timeout -- network is available due to prev. check
                    showErrorMessage(getString(R.string.error_server_message));

                    AnalyticsHelper.sendErrorHit(t, false);

                    binding.locationListPuller.setRefreshing(false);
                }
            });
        } else {
            if (!goingToMachineActivity) {
                getLaundryCall();
            } else {
                Intent intent = new Intent(LocationActivity.this, MachineActivity.class);
                Bundle b = new Bundle();
                b.putString("locationName", lastRoom);
                intent.putExtras(b);
                startActivity(intent);
            }
        }
    }

    public void showErrorMessage(String message) {
        error = true;
        binding.locationErrorText.setText(message);
        binding.recyclerView.setAdapter(null);
        binding.progressBar.setVisibility(View.GONE);
        binding.locationListPuller.setRefreshing(false);
        binding.locationErrorText.setVisibility(View.VISIBLE);
        binding.locationErrorButton.setVisibility(View.VISIBLE);
    }

    public void hideErrorMessage() {
        error = false;
        binding.locationErrorText.setVisibility(View.GONE);
        binding.locationErrorButton.setVisibility(View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.clear();
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onRefresh() {
        getRoomsCall(false, null);
        binding.locationListPuller.setRefreshing(true);
    }

    private void showNoInternetDialog() {
        error = true;
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle("Connection Error");
        alertDialogBuilder.setMessage("You have no internet connection");
        alertDialogBuilder.setCancelable(false);
        alertDialogBuilder.setPositiveButton("Okay", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                showErrorMessage("You have no internet connection");
            }
        });
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    @Override
    public void onClick(View v) {
        if (v.equals(binding.locationErrorButton)) {
            binding.progressBar.setVisibility(View.VISIBLE);
            getRoomsCall(false, null);
        }
    }
}
