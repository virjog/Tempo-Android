package com.example.nikhil.tempo;


import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import java.lang.Integer;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.example.nikhil.tempo.ApplicationController.ApplicationController;
import com.google.android.gms.common.api.ResultCallback;
import com.example.nikhil.tempo.Models.Song;
import com.example.nikhil.tempo.MusicController.MusicController;
import com.example.nikhil.tempo.Services.ActivityRecognitionService;
import com.example.nikhil.tempo.Services.MusicService;
import com.example.nikhil.tempo.Services.MusicService.MusicBinder;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.places.PlaceLikelihood;
import com.google.android.gms.location.places.PlaceLikelihoodBuffer;
import com.google.android.gms.location.places.Places;

import android.widget.MediaController.MediaPlayerControl;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;


import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;


public class MainActivity extends AppCompatActivity implements MediaPlayerControl, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    private boolean permissionsGranted = false;
    private static final int PERMISSIONS_REQUEST_CODE = 100;
    public static ArrayList<Song> songs = new ArrayList<Song>();

    //service
    private MusicService musicSrv;
    private Intent playIntent;
    //binding
    private boolean musicBound=false;

    //controller
    private MusicController controller;

    //activity and playback pause flags
    private boolean paused=false, playbackPaused=false;

    private GoogleApiClient googleApiClient;
    private boolean placeClicked = false;
    private double currentLatitude;
    private double currentLongitude;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkAndRequestPermissions();
        Button button = (Button) findViewById(R.id.trigger_button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                musicSrv.playSong();
                controller.show();
            }
        });

        Button button1 = (Button) findViewById(R.id.place_button);
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                guessCurrentPlace();
                placeClicked = true;
            }
        });


        Button button2 = (Button) findViewById(R.id.weather_button);
        button2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(placeClicked)
                {

                    final String URL = "http://api.wunderground.com/api/663639f0328f1895/conditions/q/"+currentLatitude+","+currentLongitude+".json";
                    JsonObjectRequest jsObjRequest = new JsonObjectRequest
                            (Request.Method.GET, URL, null, new Response.Listener<JSONObject>() {

                                @Override
                                public void onResponse(JSONObject response) {
                                    try
                                    {
                                        Log.v("Tempo", "Response: "+response.toString(4));
                                    }
                                   catch(Exception e)
                                   {
                                       Log.e("Tempo", e.toString());
                                   }
                                }
                            }, new Response.ErrorListener() {

                                @Override
                                public void onErrorResponse(VolleyError error) {
                                    // TODO Auto-generated method stub

                                }
                            });
                    ApplicationController.requestQueue.add(jsObjRequest);
                    placeClicked = false;
                }
                else
                {
                    Toast.makeText(getApplicationContext(), "Need to know your place first", Toast.LENGTH_SHORT).show();
                }
            }
        });
        initMp3FilesList();
        setController();


        googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(ActivityRecognition.API)
                .addApi( Places.GEO_DATA_API )
                .addApi( Places.PLACE_DETECTION_API )
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        googleApiClient.connect();
    }

    //connect to the service
    private ServiceConnection musicConnection = new ServiceConnection(){

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if(!songs.isEmpty())
            {
                Log.v("Tempo", "song list is not empty");
            }
            MusicBinder binder = (MusicBinder)service;
            //get service
            musicSrv = binder.getService();
            //pass list
            musicSrv.setList(songs);
            musicBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicBound = false;
        }
    };

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Intent intent = new Intent( this, ActivityRecognitionService.class );
        PendingIntent pendingIntent = PendingIntent.getService( this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(googleApiClient, 5000, pendingIntent);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onResume()
    {
        super.onResume();
        if(permissionsGranted)
        {
            scanDeviceForMp3Files();
        }

        if(paused)
        {
            setController();
            paused=false;
        }
    }

    public void initMp3FilesList()
    {
        if(permissionsGranted)
        {
            scanDeviceForMp3Files();
        }
    }

    public void checkAndRequestPermissions() {
        int readExtResult = ContextCompat.checkSelfPermission(getApplicationContext(), READ_EXTERNAL_STORAGE);
        int writeExtResult = ContextCompat.checkSelfPermission(getApplicationContext(), WRITE_EXTERNAL_STORAGE);
        int locationFineResult = ContextCompat.checkSelfPermission(getApplicationContext(), ACCESS_FINE_LOCATION);
        int locationCoarseResult = ContextCompat.checkSelfPermission(getApplicationContext(), ACCESS_COARSE_LOCATION);

        if ((readExtResult != PackageManager.PERMISSION_GRANTED) || (writeExtResult != PackageManager.PERMISSION_GRANTED) || (locationFineResult != PackageManager.PERMISSION_GRANTED) || (locationCoarseResult != PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(this, new String[]{READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION}, PERMISSIONS_REQUEST_CODE);
        } else {
            permissionsGranted = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.length == 4 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED && grantResults[2] == PackageManager.PERMISSION_GRANTED && grantResults[3] == PackageManager.PERMISSION_GRANTED)
            {
                Toast.makeText(getApplicationContext(), "Tempo Permissions are granted!", Toast.LENGTH_SHORT).show();
                permissionsGranted = true;
            }
            else
            {
                Toast.makeText(getApplicationContext(), "Need to enable Permissions!", Toast.LENGTH_LONG).show();
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }
    }

    private void scanDeviceForMp3Files()
    {
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
        };
        final String sortOrder = MediaStore.Audio.AudioColumns.TITLE + " ASC";


        Cursor cursor = null;
        try {
            Uri uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            cursor = getContentResolver().query(uri, projection, selection, null, sortOrder);
            if( cursor != null){
                cursor.moveToFirst();


                while( !cursor.isAfterLast() ){
                    int songid = cursor.getInt(0);
                    String title = cursor.getString(1);
                    String path = cursor.getString(2);
                    String songDuration = cursor.getString(3);
                    cursor.moveToNext();
                    if(path != null && path.endsWith(".mp3")) {
                        Song song = parseInfo(title, songDuration);
                        song.setSongID(songid);
                        song.setSongPath(path);
                        songs.add(song);
                    }
                }

            }

        } catch (Exception e) {
            Log.e("TAG", e.toString());
        }finally{
            if( cursor != null){
                cursor.close();
            }
        }
    }

    private void guessCurrentPlace()
    {

        try
        {
            final int count = 0;
            PendingResult<PlaceLikelihoodBuffer> result = Places.PlaceDetectionApi.getCurrentPlace(googleApiClient, null);
            result.setResultCallback( new ResultCallback<PlaceLikelihoodBuffer>() {
                @Override
                public void onResult( PlaceLikelihoodBuffer likelyPlaces ) {

                    for(PlaceLikelihood p : likelyPlaces)
                    {
                        if(count == 0)
                        {
                            currentLatitude = p.getPlace().getLatLng().latitude;
                            currentLongitude = p.getPlace().getLatLng().longitude;
                        }
                        Log.v("Tempo", p.getPlace().getName().toString() + " " + ( p.getLikelihood() * 100));
                    }
                    likelyPlaces.release();
                }
            });
        }
        catch(SecurityException se)
        {
            Log.e("Tempo", se.getLocalizedMessage());
        }
    }

    private Song parseInfo(String fileName, String duration)
    {
        String[] splitInfo = fileName.split("-");
        Song song = new Song();
        song.setSongArtist(splitInfo[0]);
        song.setSongTitle(splitInfo[1]);

        int convertedValue = Integer.parseInt(duration);
        song.setSongDuration(convertedValue);
        song.setSongDurationMinutesAndSeconds(parseDuration(convertedValue));
        return song;
    }

    private String parseDuration(int duration)
    {
        int minutesValue = (duration / 1000) / 60;
        int secondsValue = (duration - (minutesValue * 60 * 1000)) / 1000;
        return minutesValue+":"+secondsValue;
    }

    //set the controller up
    private void setController(){
        controller = new MusicController(this);
        //set previous and next button listeners
        controller.setPrevNextListeners(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNext();
            }
        }, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playPrev();
            }
        });
        //set and show
        controller.setMediaPlayer(this);
        controller.setAnchorView(findViewById(R.id.anchor));
        controller.setEnabled(true);
    }

    private void playNext(){
        musicSrv.playNext();
        if(playbackPaused){
            setController();
            playbackPaused=false;
        }
        controller.show(0);
    }

    private void playPrev(){
        musicSrv.playPrev();
        if(playbackPaused){
            setController();
            playbackPaused=false;
        }
        controller.show(0);
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return true;
    }

    @Override
    public boolean canSeekForward() {
        return true;
    }

    @Override
    public int getAudioSessionId() {
        return 0;
    }

    @Override
    public int getBufferPercentage() {
        return 0;
    }

    @Override
    public int getCurrentPosition() {
        if(musicSrv!=null && musicBound && musicSrv.isPng())
            return musicSrv.getPosn();
        else return 0;
    }

    @Override
    public int getDuration() {
        if(musicSrv!=null && musicBound && musicSrv.isPng())
            return musicSrv.getDur();
        else return 0;
    }

    @Override
    public boolean isPlaying() {
        if(musicSrv!=null && musicBound)
            return musicSrv.isPng();
        return false;
    }

    @Override
    public void pause() {
        playbackPaused=true;
        musicSrv.pausePlayer();
    }

    @Override
    public void seekTo(int pos) {
        musicSrv.seek(pos);
    }

    @Override
    public void start() {
        musicSrv.go();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(playIntent==null){
            Log.v("Tempo", "starting service");
            playIntent = new Intent(this, MusicService.class);
            getApplicationContext().bindService(playIntent, musicConnection, Context.BIND_AUTO_CREATE);
            startService(playIntent);
        }
    }

    @Override
    protected void onPause(){
        super.onPause();
        controller.hide();
        paused=true;
    }

    @Override
    protected void onStop() {
        controller.hide();
        googleApiClient.disconnect();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        musicBound = false;
        stopService(playIntent);
        getApplicationContext().unbindService(musicConnection);
        musicSrv=null;
        Log.v("Tempo", "in onDestroy");
        super.onDestroy();
    }

}
