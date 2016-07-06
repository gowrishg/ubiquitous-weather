/*
 * Copyright (C) 2014 The Android Open Source Project
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

package in.kudu.weatherwear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final String TAG = MyWatchFace.class.getSimpleName();
    float mLineSpace;
    float mWordSpace;
    SimpleDateFormat mDateFormat = new SimpleDateFormat("ccc, MMM d yyyy", Locale.getDefault());

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }


    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        GoogleApiClient googleApiClient = null;
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mMinutesPaint;
        Paint mInformationLightTextPaint;
        Paint mMinTextPaint;
        Paint mMaxTextPaint;
        Paint mLinePaint;
        boolean mAmbient;
        String weatherTempHigh;
        String weatherTempLow;
        Bitmap weatherTempIcon;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = new Paint();
            mHourPaint = createBoldPaint(resources.getColor(R.color.primary_text));

            mMinutesPaint = new Paint();
            mMinutesPaint = createNormalPaint(resources.getColor(R.color.primary_text));

            mInformationLightTextPaint = new Paint();
            mInformationLightTextPaint = createNormalPaint(resources.getColor(R.color.secondary_text));

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.secondary_text));
            mLinePaint.setStrokeWidth(0.5f);
            mLinePaint.setAntiAlias(true);

            mMaxTextPaint = new Paint();
            mMaxTextPaint = createNormalPaint(resources.getColor(R.color.primary_text));

            mMinTextPaint = new Paint();
            mMinTextPaint = createNormalPaint(resources.getColor(R.color.secondary_text));

            //weatherTempIcon = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);

            mTime = new Time();

            googleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this).build();
            googleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createBoldPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createNormalPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(R.dimen.digital_text_size);
            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            float tempTextSize = resources.getDimension(R.dimen.temp_text_size);
            mLineSpace = resources.getDimension(R.dimen.text_linespace);
            mWordSpace = resources.getDimension(R.dimen.text_wordspace);


            mHourPaint.setTextSize(textSize);
            mMinutesPaint.setTextSize(textSize);
            mInformationLightTextPaint.setTextSize(dateTextSize);
            mMinTextPaint.setTextSize(tempTextSize);
            mMaxTextPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mBackgroundPaint.setColor(inAmbientMode ? getResources().getColor(R.color.background_ambient) : getResources().getColor(R.color.background));
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutesPaint.setAntiAlias(!inAmbientMode);
                    mInformationLightTextPaint.setAntiAlias(!inAmbientMode);
                    mLinePaint.setAntiAlias(!inAmbientMode);
                    mMinTextPaint.setAntiAlias(!inAmbientMode);
                    mMaxTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            int centerX = bounds.width() / 2;
            long now = System.currentTimeMillis();
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(now);
            Date date = new Date();


            String hrText = String.format("%02d:", mTime.hour);
            String minText = String.format("%02d", mTime.minute);
            Rect textBounds = new Rect();
            mHourPaint.getTextBounds(hrText, 0, hrText.length(), textBounds);
            float left = centerX - textBounds.width();
            left = left < 0 ? 0 : left;
            float totalBottomY = mYOffset + textBounds.bottom;
            canvas.drawText(hrText, left, totalBottomY, mHourPaint);
            canvas.drawText(minText, left + textBounds.right, totalBottomY, mMinutesPaint);

            String dateText = mDateFormat.format(date).toUpperCase();
            mInformationLightTextPaint.getTextBounds(dateText, 0, dateText.length(), textBounds);
            left = bounds.width() - textBounds.width();
            left = left < 0 ? 0 : left / 2;
            totalBottomY = totalBottomY + textBounds.height() + mLineSpace;
            canvas.drawText(dateText, left, totalBottomY, mInformationLightTextPaint);


            if (!mAmbient) {
                totalBottomY = totalBottomY + 2 * mLineSpace;
                canvas.drawLine(centerX - 20, totalBottomY - 1, centerX + 20, totalBottomY + 1, mLinePaint);
                if (TextUtils.isEmpty(weatherTempHigh) || TextUtils.isEmpty(weatherTempLow)) {
                    String naText = getString(R.string.not_available);
                    mInformationLightTextPaint.getTextBounds(naText, 0, naText.length(), textBounds);
                    left = bounds.width() - textBounds.width();
                    left = left < 0 ? 0 : left / 2;
                    totalBottomY = totalBottomY + 2 * mLineSpace + textBounds.height();
                    canvas.drawText(naText, left, totalBottomY, mInformationLightTextPaint);
                } else {
                    String text = weatherTempHigh;
                    mMaxTextPaint.getTextBounds(text, 0, text.length(), textBounds);
                    totalBottomY = totalBottomY + 2 * mLineSpace + textBounds.height();
                    canvas.drawText(text, centerX - textBounds.width() / 2, totalBottomY, mMaxTextPaint);

                    text = weatherTempLow;
                    canvas.drawText(text, centerX + textBounds.width() / 2 + mWordSpace, totalBottomY, mMinTextPaint);

                    if (weatherTempIcon != null) {
                        // draw weather icon
                        canvas.drawBitmap(weatherTempIcon,
                                centerX - textBounds.width() / 2 - mWordSpace - weatherTempIcon.getWidth(),
                                totalBottomY - weatherTempIcon.getHeight() / 2 - textBounds.height() / 2, null);
                    }
                }
            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.e(TAG, "onConnected(): Successfully connected to Google API client");
            Wearable.DataApi.addListener(googleApiClient, dataListener);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e(TAG, "onConnectionSuspended(i): i=" + i);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG, "onConnectionFailed(connectionResult): connectionResult=" + connectionResult);
        }


        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_HIGH_TEMP_KEY = "weather_high_temp_key";
        private static final String WEATHER_LOW_TEMP_KEY = "weather_low_temp_key";
        private static final String WEATHER_ICON_KEY = "weather_icon_key";
        DataApi.DataListener dataListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                Log.e(TAG, "onDataChanged(): " + dataEvents);

                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        String path = event.getDataItem().getUri().getPath();
                        if (WEATHER_PATH.equals(path)) {
                            Log.e(TAG, "Data Changed for " + WEATHER_PATH);
                            try {
                                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                                weatherTempHigh = dataMapItem.getDataMap().getString(WEATHER_HIGH_TEMP_KEY);
                                weatherTempLow = dataMapItem.getDataMap().getString(WEATHER_LOW_TEMP_KEY);
                                Asset photo = dataMapItem.getDataMap().getAsset(WEATHER_ICON_KEY);
                                weatherTempIcon = loadBitmapFromAsset(googleApiClient, photo);
                            } catch (Exception e) {
                                Log.e(TAG, "Exception   ", e);
                                weatherTempIcon = null;
                            }

                        } else {

                            Log.e(TAG, "Unrecognized path:  \"" + path + "\"  \"" + WEATHER_PATH + "\"");
                        }

                    } else {
                        Log.e(TAG, "Unknown data event type   " + event.getType());
                    }
                }
            }

            /**
             * Get image from the asset
             * @param apiClient
             * @param asset
             * @return
             */
            private Bitmap loadBitmapFromAsset(GoogleApiClient apiClient, Asset asset) {
                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(apiClient, asset).await().getInputStream();
                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                return BitmapFactory.decodeStream(assetInputStream);
            }
        };
    }
}