/*
 * Copyright (C) 2018 Mauricio Rodriguez (ranametal@users.sf.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmsoft.pentaxgallery.data.provider;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.widget.Toast;

import com.hmsoft.pentaxgallery.BuildConfig;
import com.hmsoft.pentaxgallery.MyApplication;
import com.hmsoft.pentaxgallery.R;
import com.hmsoft.pentaxgallery.camera.model.ImageData;
import com.hmsoft.pentaxgallery.camera.model.ImageList;
import com.hmsoft.pentaxgallery.data.model.DownloadEntry;
import com.hmsoft.pentaxgallery.service.DownloadService;
import com.hmsoft.pentaxgallery.ui.ImageGridActivity;
import com.hmsoft.pentaxgallery.util.Logger;
import com.hmsoft.pentaxgallery.util.cache.CacheUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

public class DownloadQueue {

    private static final String CACHE_KEY = "downloadQueue.cache";

    private static final String TAG = "DownloadQueue";
    private static final String CHANNEL_ID = "DownloadChannel";
  
    private static final int PROGRESS_NOTIFICATION_ID = 5;
    private static final int DONE_NOTIFICATION_ID = 6;

    private static Hashtable<Integer, DownloadEntry> sDownloadQueueDict = null;
    private static List<DownloadEntry> sDownloadQueue;
    private final static ImageList sIageList = new DownloadQueueImageList();
    private static final String[] sFileToScan = new String[1];

    private static PowerManager.WakeLock sWackeLock;

  
    private static int downloadCount = 0;

    public static boolean inBatchDownload;

    private static OnDowloadFinishedListener onDowloadFinishedListener;

    public interface OnDowloadFinishedListener {
        void onDownloadFinished(ImageData imageData, long donloadId, int remainingDownloads, boolean wasCanceled);
        void onDownloadProgress(ImageData imageData, long donloadId, int progress);
    }

    public static ResultReceiver DownloadResultReceiver = new DownloadReceiver(new Handler());

    private static class DownloadReceiver extends ResultReceiver {

        public DownloadReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {

            super.onReceiveResult(resultCode, resultData);
            Context context = MyApplication.ApplicationContext;

            int id = resultData.getInt(DownloadService.EXTRA_DOWNLOAD_ID);
            DownloadEntry downloadEntry = DownloadQueue.findDownloadEntry(id);

            if (resultCode == DownloadService.UPDATE_PROGRESS) {
                int progress = resultData.getInt(DownloadService.EXTRA_PROGRESS);
                if(BuildConfig.DEBUG) Logger.debug(TAG, "Update Progress: " + id + " - " + progress + "%");
                if(downloadEntry != null) {
                    downloadNotification(downloadEntry.getImageData(), progress);
                    downloadEntry.setProgress(progress);
                    doDownloadProgress(downloadEntry.getImageData(), id, progress);
                }
            } else if (resultCode == DownloadService.DOWNLOAD_FINISHED) {
                if(BuildConfig.DEBUG) Logger.debug(TAG, "Finished: " + id);
                if (downloadEntry != null) {

                    int status = resultData.getInt(DownloadService.EXTRA_DOWNLOAD_STATUS);

                    if(status == DownloadService.DOWNLOAD_STATUS_SUCCESS) {
                        sFileToScan[0] = downloadEntry.getImageData().getLocalPath().getAbsolutePath();
                        MediaScannerConnection.scanFile(context, sFileToScan, null, null);
                        downloadCount++;
                    } else if(status == DownloadService.DOWNLOAD_STATUS_ERROR) {
                        String message = resultData.getString(DownloadService.EXTRA_DOWNLOAD_STATUS_MESSAGE);
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                    } else if(status == DownloadService.DOWNLOAD_STATUS_CANCELED) {
                        Toast.makeText(context, "Download canceled: " + downloadEntry.getImageData().fileName, Toast.LENGTH_LONG).show();
                    } else if(status == DownloadService.DOWNLOAD_STATUS_TOO_MANY_ERRORS) {
                        Toast.makeText(context, "Too many errors.", Toast.LENGTH_LONG).show();                        
                        cancelAll();
                        return;
                    }

                    downloadNotification(downloadEntry.getImageData(), 100);
                    DownloadQueue.remove(downloadEntry, false);
                    DownloadQueue.processDownloadQueue();
                }
            }
        }
    }

    public static void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = MyApplication.ApplicationContext;
            String name = context.getString(R.string.download_notification_channel_name);
            String description = context.getString(R.string.download_notification_channel_desc);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setSound(null,null);
            channel.enableLights(false);
            channel.enableVibration(false);

            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = MyApplication.ApplicationContext.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    public static void downloadNotification(ImageData imageData, int progress) {
        Context context = MyApplication.ApplicationContext;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

        PendingIntent pi = ImageGridActivity.getPendingIntent();
        builder.setSmallIcon(R.drawable.ic_cloud_download_white_24dp)                   
                   .setLocalOnly(true)                   
                   .setPriority(NotificationCompat.PRIORITY_DEFAULT)                   
                   .setContentIntent(pi);
      
        if(imageData != null) {
            //i.putExtra(ImageGridActivity.EXTRA_START_DOWNLOADS, true);
          
            builder.setContentTitle(context.getString(R.string.download_notification_title))
                   .setContentText(String.format("%s (%d)", imageData.fileName, sDownloadQueue.size()))
                   .setOngoing(true)                   
                   .setLargeIcon(imageData.getThumbBitmap())
                   .setProgress(100, progress, progress == 0);

            notificationManager.cancel(DONE_NOTIFICATION_ID);
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, builder.build());
        } else {
            notificationManager.cancel(PROGRESS_NOTIFICATION_ID);
          
            if(downloadCount > 0) {
                builder.setContentText(String.format(context.getString(R.string.download_done_notification_text), downloadCount))
                       .setContentTitle(context.getString(R.string.download_done_notification_title));
                notificationManager.notify(DONE_NOTIFICATION_ID, builder.build());
                downloadCount = 0;
            }

            if(progress > -1) {
               Toast.makeText(context, R.string.donwload_done_notification_title, Toast.LENGTH_LONG).show();     
            }
        }
    }

    public static void loadFromCache(ImageList sourceImageList, boolean forceLoad) {
        if(sourceImageList == null || sourceImageList.length() == 0) {
            return;
        }

        if(sDownloadQueue == null || forceLoad) {
            String cacheValue = CacheUtils.getString(CACHE_KEY);
            if(cacheValue == null) {
                return;
            }

            sDownloadQueue = new ArrayList<DownloadEntry>();
            try {
                JSONArray jsonArray = new JSONArray(cacheValue);
                for(int c = 0; c < jsonArray.length(); c++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(c);
                    String fileName = jsonObject.getString(DownloadEntry.UNIQUE_FILE_NAME);
                    ImageData imageData = sourceImageList.findByUniqueFileName(fileName);
                    if(imageData != null) {
                        DownloadEntry downloadEntry = new DownloadEntry(imageData);
                        sDownloadQueue.add(downloadEntry);
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public static void saveToCache() {
        JSONArray jsonArray = new JSONArray();
        if(sDownloadQueue != null) {
            for (DownloadEntry downloadEntry : sDownloadQueue) {
                JSONObject jsonObject = downloadEntry.toJSONbject();
                if(jsonObject != null) {
                    jsonArray.put(jsonObject);
                }
            }
        }
        CacheUtils.saveString(CACHE_KEY, jsonArray.toString());
    }

    public static void setOnDowloadFinishedListener(OnDowloadFinishedListener onDowloadFinishedListener) {
        DownloadQueue.onDowloadFinishedListener = onDowloadFinishedListener;
    }

    private static void doDownloadProgress(ImageData imageData, long donloadId, int progress) {
        if(onDowloadFinishedListener != null) {
            onDowloadFinishedListener.onDownloadProgress(imageData, donloadId, progress);
        }
    }

    private static void doDowloadFinished(ImageData imageData, long donloadId, boolean wasCanceled) {
        if(!wasCanceled) {
            imageData.updateExistsOnLocasStorage();
        }
        if(onDowloadFinishedListener != null) {
            onDowloadFinishedListener.onDownloadFinished(imageData, donloadId, sDownloadQueue.size(), wasCanceled);
        }

        if(sDownloadQueue.size() == 0) {
            inBatchDownload = false;
            if(sWackeLock != null) {
                sWackeLock.release();
                sWackeLock = null;
                if(BuildConfig.DEBUG) Logger.debug(TAG, "WakeLock released");
            }
            downloadNotification(null, donloadId > 0 ? 0 : -1);
        }
    }

    public static DownloadEntry getNextDownload() {
        return findDownloadEntry(0);
    }

    public static DownloadEntry findDownloadEntry(ImageData imageData) {
        if (sDownloadQueue == null) {
            return null;
        }

        for (DownloadEntry downloadEntry : sDownloadQueue) {
            if (downloadEntry.getImageData() == imageData) {
                return downloadEntry;
            }
        }

        return null;
    }

    public static DownloadEntry findDownloadEntry(int downloadId) {
        if (sDownloadQueue == null) {
            return null;
        }

         if (sDownloadQueueDict == null) {
             sDownloadQueueDict = new Hashtable<>();
         }

         if(downloadId > 0 && sDownloadQueueDict.containsKey(downloadId)) {
             return sDownloadQueueDict.get(downloadId);
         }

        for (DownloadEntry downloadEntry : sDownloadQueue) {
            if (downloadEntry.getDownloadId() == downloadId) {
                if(BuildConfig.DEBUG) Logger.debug(TAG, "Found download entry in list: " + downloadId);
                sDownloadQueueDict.put(downloadId, downloadEntry);
                return downloadEntry;
            }
        }

        if(BuildConfig.DEBUG) Logger.warning(TAG, "No download entry found: " + downloadId);

        return null;
    }

    public static boolean isDownloading() {
        if (sDownloadQueue == null) {
            return false;
        }

        for (DownloadEntry downloadEntry : sDownloadQueue) {
            if (downloadEntry.getDownloadId() > 0) {
                if(BuildConfig.DEBUG) Logger.debug(TAG, "Downloading:" + downloadEntry.getDownloadId());
                return true;
            }
        }

        if(BuildConfig.DEBUG) Logger.debug(TAG, "Not downloading");
        return false;
    }

    public static DownloadEntry addDownloadQueue(ImageData imageData) {
        return addDownloadQueue(imageData, DownloadQueue.inBatchDownload);
    }
  
    public static DownloadEntry addDownloadQueue(ImageData imageData, boolean atTheBeginning) {
        if (sDownloadQueue == null) {
            sDownloadQueue = new ArrayList<DownloadEntry>();
        }

        DownloadEntry downloadEntry = findDownloadEntry(imageData);
        if(downloadEntry == null) {
            downloadEntry = new DownloadEntry(imageData);
        } else if(atTheBeginning) {
            sDownloadQueue.remove(downloadEntry);
        } else {
            downloadEntry = null;
        } 
      
        if (downloadEntry != null) {
            imageData.setIsInDownloadQueue(true);
            if(atTheBeginning) {
              sDownloadQueue.add(0, downloadEntry);
            } else {
              sDownloadQueue.add(downloadEntry);
            }
            processDownloadQueue();
            return downloadEntry;
        }
        return null;
    }

    public static void removeFromDownloadQueue(ImageData imageData) {

        DownloadEntry downloadEntry = findDownloadEntry(imageData);
        imageData.setIsInDownloadQueue(false);
        if (downloadEntry != null) {
            DownloadService.cancelDownload(downloadEntry.getDownloadId());
            remove(downloadEntry, true);
        }
    }

    public static void processDownloadQueue() {
        if (!isDownloading()) {
            DownloadEntry downloadEntry = getNextDownload();
            if (downloadEntry != null) {
                download(downloadEntry);
            }  else if(BuildConfig.DEBUG) {
                Logger.debug(TAG, "No next download found");
            }
        }
    }

    public static void download(ImageData imageData) {
        DownloadEntry downloadEntry = findDownloadEntry(imageData);
        if (downloadEntry != null) {
            download(downloadEntry);
        }
    }

    private static void download(DownloadEntry downloadEntry) {

        ImageData imageData = downloadEntry.getImageData();
        String url = imageData.getDownloadUrl();

        Context context = MyApplication.ApplicationContext;

        downloadNotification(imageData, 0);

        File parentDir = imageData.getLocalPath().getParentFile();
        parentDir.mkdirs();

        int id = DownloadService.download(context, url, imageData.getLocalPath().getAbsolutePath());
        downloadEntry.setDownloadId(id);

        if(sWackeLock == null) {
            sWackeLock = MyApplication.acquireWakeLock();
        }

        if(BuildConfig.DEBUG) Logger.debug(TAG, "Starting download: " + imageData.fileName + ", ID: " + id);
    }

    private static void cancelAll() {
        DownloadService.cancelCurrentDownload();
        for(DownloadEntry downloadEntry : sDownloadQueue) {
            downloadEntry.getImageData().setIsInDownloadQueue(false);
        }
        sDownloadQueue.clear();
        doDowloadFinished(null, -1, true);        
    } 

    private static void remove(DownloadEntry downloadEntry, boolean canceled) {
        sDownloadQueue.remove(downloadEntry);
        downloadEntry.getImageData().updateExistsOnLocasStorage();
        downloadEntry.getImageData().setIsInDownloadQueue(false);
        doDowloadFinished(downloadEntry.mImageData, downloadEntry.getDownloadId(), canceled);
    }

    public static ImageList getImageList() {
        return sIageList;
    }

    private static class DownloadQueueImageList extends ImageList {

        @Override
        protected ImageData createImageData(String dirName, String fileName) {
            return null;
        }

        @Override
        public ImageData getImage(int index) {
            if (sDownloadQueue == null) {
                return null;
            }
            if(index < sDownloadQueue.size()) {
                return sDownloadQueue.get(index).mImageData;
            }
            return null;
        }

        @Override
        public int length() {
            if (sDownloadQueue == null) {
                return 0;
            }
            return sDownloadQueue.size();
        }
    }
}
