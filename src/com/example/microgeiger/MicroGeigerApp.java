/*
    Copyright 2013 Dmytry Lavrov

    This file is part of MicroGeiger for Android.

    MicroGeiger is free software: you can redistribute it and/or modify it under the terms of the 
    GNU General Public License as published by the Free Software Foundation, either version 2 
    of the License, or (at your option) any later version.

    MicroGeiger is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
    See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with MicroGeiger. 
    If not, see http://www.gnu.org/licenses/.
*/

package com.example.microgeiger;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder.AudioSource;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.widget.TextView;

public class MicroGeigerApp extends Application {
	private static final String TAG = "MicroGeiger";
	public volatile int total_count=0;
	public final int sample_rate=44100;
	public final int counters_update_rate=2;/// sample rate must be divisible by counters update rate
	public final int samples_per_update=sample_rate/counters_update_rate;
	public volatile boolean changed=false;
	boolean started=false;
	public class Counter{
		public int counts[];
		public int pos=0;		
		volatile public int count=0;
		public double scale=1.0;
		public String name;
		Counter(int n_counts, double scale_, String name_){
			counts=new int[n_counts];
			scale=scale_;
			name=name_;
		}
		public void push(int n){			
			pos=pos+1;
			if(pos>=counts.length)pos=0;
			int old_count=count;
			count-=counts[pos];
			counts[pos]=n;
			count+=n;
			if(count!=old_count)changed=true;
		}
		double getValue(){
			return scale*count;
		}
	}
	public volatile Counter counters[];
  
    private class Listener implements Runnable{
    	public volatile boolean do_stop=false;
		@Override
		public void run() {	
			AudioRecord recorder;			
			int dead_time=sample_rate/2000;
			double threshold=0.1;
			double running_avg=0.0;
			double running_avg_const=0.0001;
			int dead_countdown=0;
			int click_countdown=0;
			
			int click_duration=40;
			int click_beep_divisor=10;
			
			int sample_update_counter=0;
			int sample_count=0;
			int record_min_buffer_size=AudioRecord.getMinBufferSize(sample_rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			int play_min_buffer_size=AudioTrack.getMinBufferSize(sample_rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			int min_buffer_size=Math.max(record_min_buffer_size, play_min_buffer_size);
			
			int data_size=Math.max(sample_rate/20, record_min_buffer_size);
			short data[]=new short[data_size];
			short playback_data[]=new short[data_size];
			recorder=new AudioRecord (AudioSource.MIC, sample_rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, data_size);
			
			final int play_sample_rate=44100;
			final short geiger_beep[]=new short[10];
			for(int i=0;i<geiger_beep.length;++i){
				if((i/2)%2==1){
					geiger_beep[i]=32767;
				}else{
					geiger_beep[i]=-32767;
				}
			}
			
			final AudioTrack player = new AudioTrack(AudioManager.STREAM_RING,
					sample_rate, AudioFormat.CHANNEL_CONFIGURATION_MONO,
	                AudioFormat.ENCODING_PCM_16BIT, data_size,
	                AudioTrack.MODE_STREAM);
	        player.play();
			
			try{
			    while(!do_stop){
			    	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
			    	try{
			    		threshold=Double.parseDouble(prefs.getString("threshold", ""));
			    	}catch(NumberFormatException e){			    	
			    	}
			    	try{
			    		dead_time=(int)(0.001*sample_rate*Double.parseDouble(prefs.getString("dead_time", "")));
			    	}catch(NumberFormatException e){			    	
			    	}
			    	
			    	if (recorder.getState()==android.media.AudioRecord.STATE_INITIALIZED){ // check to see if the recorder has initialized yet.
			            if (recorder.getRecordingState()==android.media.AudioRecord.RECORDSTATE_STOPPED){
			                 recorder.startRecording();
			            }else{
			            	int read_size=recorder.read(data,0,data_size);      	
			            	
			            	
			            	int old_total_count=total_count;
			            	for(int i=0; i<read_size; ++i){
			            		if(dead_countdown>0){
			            			dead_countdown--;		            			
			            		}
			            		if(click_countdown>0){
			            			click_countdown--;
			            			playback_data[i]=(short) ((click_countdown/click_beep_divisor)%2 == 1 ? 32767:-32767);
			            		}else{
			            			playback_data[i]=0;
			            		}
			            		sample_update_counter++;
			            		if(sample_update_counter>=samples_per_update){
			            			for(int j=0;j<counters.length;++j){
			            				counters[j].push(sample_count);			            				
			            			}
			            			sample_update_counter=0;
			            			sample_count=0;
			            			//Log.d(TAG, "got a sample");
			            		}
			            		double raw_v=data[i]*(1.0/32768.0);
			            		running_avg=running_avg*(1.0-running_avg_const)+raw_v*running_avg_const;
			            		double v=raw_v-running_avg;
			            		if(v>threshold || v<-threshold){
			            			if(dead_countdown<=0){
			            				total_count++;
			            				sample_count++;
			            				dead_countdown=dead_time;
			            				click_countdown=click_duration;
			            			}		            			
			            		}
			            	}
			            	if(old_total_count!=total_count){
			            		changed=true;			            		
			            	}
			            	player.write(playback_data,0,read_size);
				    	}
			    	}else{
				    	Log.d(TAG, "failed to initialize audio");
				    	Thread.sleep(5000);
				    }
			    }
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		    finally{
		    	recorder.stop();
		    	recorder.release();
		    	player.stop();
		    	player.release();
		    }
		}
	}
    
    Listener listener;
    Thread listener_thread;
    
    void init_counters(){
    	counters=new Counter[4];
    	counters[0]=new Counter(counters_update_rate*5, 12.0, " CPM in last 5 sec");
    	counters[1]=new Counter(counters_update_rate*30, 2.0, " CPM in last 30 sec");
    	counters[2]=new Counter(counters_update_rate*120, 0.5, " CPM in last 2 min");
    	counters[3]=new Counter(counters_update_rate*600, 0.1, " CPM in last 10 min");
    }
    
    void start(){
    	if(!started){	    	
	    	init_counters();
	    	if(listener_thread==null){
		        listener=new Listener();
		        listener_thread=new Thread(listener);
		        listener_thread.start();
	        }
	    	started=true;
    	}
    }
    void reset(){
    	total_count=0;
    	init_counters();
    	changed=true;
    }
    void stop(){
    	if(listener!=null){
    		listener.do_stop=true;
    		listener_thread.interrupt();
    		try {
				listener_thread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    		listener_thread=null;
    	}  
    	started=false;
    }
    @Override
	public void onCreate() {    	
        super.onCreate();   
        start();
    }
    @Override
    public void onTerminate() {    	
        super.onTerminate();
        stop();
    }
}
