/*
 * Copyright (C) 2012 The Android Open Source Project
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
 *
 * Modified by hmrs.cr
 *
 */

package com.hmsoft.pentaxgallery.util.image;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;

import com.hmsoft.pentaxgallery.BuildConfig;
import com.hmsoft.pentaxgallery.camera.Camera;
import com.hmsoft.pentaxgallery.camera.model.CameraPreferences;
import com.hmsoft.pentaxgallery.camera.model.ImageData;
import com.hmsoft.pentaxgallery.util.Logger;
import com.hmsoft.pentaxgallery.util.Utils;
import com.hmsoft.pentaxgallery.util.cache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A simple subclass of {@link ImageResizer} that fetches and resizes images fetched from a URL.
 */
public class ImageFetcher extends ImageResizer {
    private static final String TAG = "ImageFetcher";
    private static final int HTTP_CACHE_SIZE = 512 * 1024 * 1024; // 512MB
    private static final String HTTP_CACHE_DIR = "http";
    private static final int IO_BUFFER_SIZE = 8 * 1024;

    private DiskLruCache mHttpDiskCache;
    private File mHttpCacheDir;
    private boolean mHttpDiskCacheStarting = true;
    private final Object mHttpDiskCacheLock = new Object();
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mCancel;
    protected ContentResolver mContentResolver;

    /**
     * Initialize providing a target image width and height for the processing images.
     *
     * @param context
     * @param imageWidth
     * @param imageHeight
     */
    public ImageFetcher(Context context, int imageWidth, int imageHeight) {
        super(context, imageWidth, imageHeight);
        init(context);
    }

    /**
     * Initialize providing a single target image size (used for both width and height);
     *
     * @param context
     * @param imageSize
     */
    public ImageFetcher(Context context, int imageSize) {
        super(context, imageSize);
        init(context);
    }

    private void init(Context context) {
        mHttpCacheDir = Utils.getDiskCacheDir(context, HTTP_CACHE_DIR);
        mContentResolver = context.getContentResolver();
    }

    @Override
    protected void initDiskCacheInternal() {
        super.initDiskCacheInternal();
        initHttpDiskCache();
    }

    private void initHttpDiskCache() {
        if (!mHttpCacheDir.exists()) {
            mHttpCacheDir.mkdirs();
        }
        synchronized (mHttpDiskCacheLock) {
            if (Utils.getUsableSpace(mHttpCacheDir) > HTTP_CACHE_SIZE) {
                try {
                    mHttpDiskCache = DiskLruCache.open(mHttpCacheDir, 1, 1, HTTP_CACHE_SIZE);
                    //CacheUtils.setDiskCache(mHttpDiskCache);
                    if (BuildConfig.DEBUG) {
                        Logger.debug(TAG, "HTTP cache initialized");
                    }
                } catch (IOException e) {
                    mHttpDiskCache = null;
                }
            }
            mHttpDiskCacheStarting = false;
            mHttpDiskCacheLock.notifyAll();
        }
    }

    @Override
    protected void clearCacheInternal() {
        super.clearCacheInternal();
        synchronized (mHttpDiskCacheLock) {
            if (mHttpDiskCache != null && !mHttpDiskCache.isClosed()) {
                try {
                    mHttpDiskCache.delete();
                    if (BuildConfig.DEBUG) {
                        Logger.debug(TAG, "HTTP cache cleared");
                    }
                } catch (IOException e) {
                    Logger.error(TAG, "clearCacheInternal - " + e);
                }
                mHttpDiskCache = null;
                mHttpDiskCacheStarting = true;
                initHttpDiskCache();
            }
        }
    }

    @Override
    protected void flushCacheInternal() {

        synchronized (mHttpDiskCacheLock) {
            if (mHttpDiskCache != null) {
                try {
                    mHttpDiskCache.flush();
                    if (BuildConfig.DEBUG) {
                        Logger.debug(TAG, "HTTP cache flushed");
                    }
                } catch (IOException e) {
                    Logger.error(TAG, "flush - " + e);
                }
            }
        }
        super.flushCacheInternal();
    }

    @Override
    protected void closeCacheInternal() {
        super.closeCacheInternal();
        synchronized (mHttpDiskCacheLock) {
            if (mHttpDiskCache != null) {
                try {
                    if (!mHttpDiskCache.isClosed()) {
                        mHttpDiskCache.close();
                        mHttpDiskCache = null;
                        if (BuildConfig.DEBUG) {
                            Logger.debug(TAG, "HTTP cache closed");
                        }
                    }
                } catch (IOException e) {
                    Logger.error(TAG, "closeCacheInternal - " + e);
                }
            }
        }
    }

    public boolean downloadUrlToCacheIfNeeded(String url) throws IOException {
        DiskLruCache.Editor editor = null;
        synchronized (mHttpDiskCacheLock) { 
           while (mHttpDiskCacheStarting) {
                try {
                    mHttpDiskCacheLock.wait();
                } catch (InterruptedException e) {
                }
            }
            final String key = ImageCache.hashKeyForDisk(url);
            if (!mHttpDiskCache.hasKey(key)) {
                if(BuildConfig.DEBUG) Logger.debug(TAG, "Cache Key not found for " + url);
                editor = mHttpDiskCache.edit(key);
            } else if(BuildConfig.DEBUG) Logger.debug(TAG, "Cache Key found for " + url);
        }
      
        boolean downloaded = false;
        if(editor != null) {
            downloaded = downloadUrlToCache(editor, url);
        }
      
        return downloaded;
    }
  
    private boolean downloadUrlToCache(DiskLruCache.Editor editor, String url) throws IOException {
        boolean success = false;
        if (editor != null && !isCancel()) {
            success = (downloadUrlToStream(url,
                    editor.newOutputStream(DISK_CACHE_INDEX)));

            synchronized (mHttpDiskCacheLock) {
                if (success) {
                    editor.commit();
                    if(BuildConfig.DEBUG) Logger.debug(TAG, "Downloaded to cache " + url);
                } else {
                    editor.abort();
                }
            }
        }
        return success;
    }
  
    /**
     * The main process method, which will be called by the UrlImageWorker in the AsyncTask background
     * thread.
     *
     * @param url The url to load the bitmap, in this case, a regular http URL
     * @return The downloaded and resized bitmap
     */
    protected Bitmap processBitmap(String url, ImageData imageData) {
        if (BuildConfig.DEBUG) {
            Logger.debug(TAG, "processBitmap - " + url);
        }

        final String key = ImageCache.hashKeyForDisk(url);
        FileDescriptor fileDescriptor = null;
        FileInputStream fileInputStream = null;
        DiskLruCache.Snapshot snapshot;
        synchronized (mHttpDiskCacheLock) {
            // Wait for disk cache to initialize
            while (mHttpDiskCacheStarting) {
                try {
                    mHttpDiskCacheLock.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        if (mHttpDiskCache != null) {
            try {
                DiskLruCache.Editor editor = null;
                synchronized (mHttpDiskCacheLock) {
                    snapshot = mHttpDiskCache.get(key);
                    if (snapshot == null) {
                        editor = mHttpDiskCache.edit(key);                      
                    }
                }

                if (snapshot == null) {
                    if (BuildConfig.DEBUG) {
                        Logger.debug(TAG, "processBitmap, not found in http cache, downloading... " + imageData);
                    }
                    downloadUrlToCache(editor, url);
                    synchronized (mHttpDiskCacheLock) {
                        snapshot = mHttpDiskCache.get(key);
                    }
                } else if (BuildConfig.DEBUG) {
                    Logger.debug(TAG, "processBitmap, found in http cache " + imageData);
                }
              
              
                if (snapshot != null) {
                    fileInputStream =
                            (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                    fileDescriptor = fileInputStream.getFD();
                }
            } catch (IOException e) {
                Logger.error(TAG, "processBitmap - " + e);
            } catch (IllegalStateException e) {
                Logger.error(TAG, "processBitmap - " + e);
            } finally {
                if (fileDescriptor == null && fileInputStream != null) {
                    try {
                        fileInputStream.close();
                    } catch (IOException e) {}
                }
            }
        }

        boolean loadLocalImageData = Camera.instance.getPreferences().loadLocalImageData();
        if (loadLocalImageData && fileInputStream == null && imageData.existsOnLocalStorage()) {
            try {
                if(BuildConfig.DEBUG) Logger.debug(TAG, "Loading picture from " + imageData.getLocalStorageUri());
                fileDescriptor = mContentResolver.openFileDescriptor(imageData.getLocalStorageUri(), "r").getFileDescriptor();
            } catch (IOException e) {
                if(BuildConfig.DEBUG) Logger.warning(TAG, "ERROR: Loading picture from " + imageData.getLocalStorageUri(), e);
                e.printStackTrace();
            }
        }

        Bitmap bitmap = null;
        if (fileDescriptor != null) {
            bitmap = decodeSampledBitmapFromDescriptor(fileDescriptor, mImageWidth,
                    mImageHeight, getImageCache());
        }
        if (fileInputStream != null) {
            try {
                fileInputStream.close();
            } catch (IOException e) {}
        }
        return bitmap;
    }

    /**
     * Download a bitmap from a URL and write the content to an output stream.
     *
     * @param urlString The URL to fetch
     * @return true if successful, false otherwise
     */
    public boolean downloadUrlToStream(String urlString, OutputStream outputStream) {

        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;

        try {

            CameraPreferences preferences = Camera.instance.getPreferences();

            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(preferences.getConnectTimeout());
            urlConnection.setReadTimeout(preferences.getReadTimeout());
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (final IOException e) {
            if(BuildConfig.DEBUG) Logger.error(TAG, "Error in downloadBitmap - " + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {}
        }
        return false;
    }

    public boolean isCancel() {
        return mCancel;
    }

    public void setCancel(boolean mCancel) {
        this.mCancel = mCancel;
    }
}
