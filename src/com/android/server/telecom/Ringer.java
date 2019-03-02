/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.AsyncTask;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.ContentResolver;
import android.content.Context;
import android.os.VibrationEffect;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Controls the ringtone player.
 */
@VisibleForTesting
public class Ringer {
    @VisibleForTesting
    public VibrationEffect mDefaultVibrationEffect;

    private static final long[] PULSE_PATTERN = {0,12,250,12,500, // priming  + interval
            50,50,50,50,50,50,50,50,50,50,50,50,50,50, // ease-in
            300, // Peak
            1000}; // pause before repetition

    private static final int[] PULSE_AMPLITUDE = {0,255,0,255,0, // priming  + interval
            77,77,78,79,81,84,87,93,101,114,133,162,205,255, // ease-in (min amplitude = 30%)
            255, // Peak
            0}; // pause before repetition

    private static final long[] SIMPLE_VIBRATION_PATTERN = {
            0, // No delay before starting
            1000, // How long to vibrate
            1000, // How long to wait before vibrating again
    };

    private static final int[] SIMPLE_VIBRATION_AMPLITUDE = {
            0, // No delay before starting
            255, // Vibrate full amplitude
            0, // No amplitude while waiting
    };

    private static final long[] CALL_WAITING_VIBRATION_PATTERN = {
            200,
            300,
            500,
    };

    /**
     * Indicates that vibration should be repeated at element 5 in the {@link #PULSE_AMPLITUDE} and
     * {@link #PULSE_PATTERN} arrays.  This means repetition will happen for the main ease-in/peak
     * pattern, but the priming + interval part will not be repeated.
     */
    private static final int REPEAT_VIBRATION_AT = 5;

    private static final int REPEAT_SIMPLE_VIBRATION_AT = 1;

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build();

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */

    private final SystemSettingsUtil mSystemSettingsUtil;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final AsyncRingtonePlayer mRingtonePlayer;
    private final Context mContext;
    private final Vibrator mVibrator;
    private final InCallController mInCallController;

    private TorchToggler torchToggler;

    private InCallTonePlayer mCallWaitingPlayer;
    private RingtoneFactory mRingtoneFactory;

    /**
     * Call objects that are ringing, vibrating or call-waiting. These are used only for logging
     * purposes.
     */
    private Call mRingingCall;
    private Call mVibratingCall;
    private Call mCallWaitingCall;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private boolean mIsVibrating = false;

    private int torchMode;

    /** Initializes the Ringer. */
    @VisibleForTesting
    public Ringer(
            InCallTonePlayer.Factory playerFactory,
            Context context,
            SystemSettingsUtil systemSettingsUtil,
            AsyncRingtonePlayer asyncRingtonePlayer,
            RingtoneFactory ringtoneFactory,
            Vibrator vibrator,
            InCallController inCallController) {

        mSystemSettingsUtil = systemSettingsUtil;
        mPlayerFactory = playerFactory;
        mContext = context;
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = vibrator;
        mRingtonePlayer = asyncRingtonePlayer;
        mRingtoneFactory = ringtoneFactory;
        mInCallController = inCallController;

        torchToggler = new TorchToggler(context);

        if (mContext.getResources().getBoolean(R.bool.use_simple_vibration_pattern)) {
            mDefaultVibrationEffect = VibrationEffect.createWaveform(SIMPLE_VIBRATION_PATTERN,
                    SIMPLE_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
        } else {
            mDefaultVibrationEffect = VibrationEffect.createWaveform(PULSE_PATTERN,
                    PULSE_AMPLITUDE, REPEAT_VIBRATION_AT);
        }
    }

    public boolean startRinging(Call foregroundCall, boolean isHfpDeviceAttached) {
        if (foregroundCall == null) {
            Log.wtf(this, "startRinging called with null foreground call.");
            return false;
        }

        AudioManager audioManager =
                (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean isVolumeOverZero = audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0;
        boolean shouldRingForContact = shouldRingForContact(foregroundCall.getContactUri());
        boolean isRingtonePresent = !(mRingtoneFactory.getRingtone(foregroundCall) == null);
        boolean isSelfManaged = foregroundCall.isSelfManaged();

        boolean isRingerAudible = isVolumeOverZero && shouldRingForContact && isRingtonePresent;
        boolean hasExternalRinger = hasExternalRinger(foregroundCall);
        // Acquire audio focus under any of the following conditions:
        // 1. Should ring for contact and there's an HFP device attached
        // 2. Volume is over zero, we should ring for the contact, and there's a audible ringtone
        //    present.
        // 3. The call is self-managed.
        boolean shouldAcquireAudioFocus =
                isRingerAudible || (isHfpDeviceAttached && shouldRingForContact) || isSelfManaged;

        // Don't do call waiting operations or vibration unless these are false.
        boolean isTheaterModeOn = mSystemSettingsUtil.isTheaterModeOn(mContext);
        boolean letDialerHandleRinging = mInCallController.doesConnectedDialerSupportRinging();
        boolean endEarly = isTheaterModeOn || letDialerHandleRinging || isSelfManaged ||
                hasExternalRinger;

        if (endEarly) {
            if (letDialerHandleRinging) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING);
            }
            Log.i(this, "Ending early -- isTheaterModeOn=%s, letDialerHandleRinging=%s, " +
                    "isSelfManaged=%s, hasExternalRinger=%s", isTheaterModeOn,
                    letDialerHandleRinging, isSelfManaged, hasExternalRinger);
            return shouldAcquireAudioFocus;
        }

        stopCallWaiting();

        VibrationEffect effect;
        if (isRingerAudible) {
            mRingingCall = foregroundCall;
            Log.addEvent(foregroundCall, LogUtils.Events.START_RINGER);

            float startVolume = 0;
            int rampUpTime = 0;

            final ContentResolver cr = mContext.getContentResolver();
            if (Settings.System.getInt(cr,
                    Settings.System.INCREASING_RING, 0) != 0) {
                startVolume = Settings.System.getFloat(cr,
                        Settings.System.INCREASING_RING_START_VOLUME, 0.1f);
                rampUpTime = Settings.System.getInt(cr,
                        Settings.System.INCREASING_RING_RAMP_UP_TIME, 20);
            }

            // Because we wait until a contact info query to complete before processing a
            // call (for the purposes of direct-to-voicemail), the information about custom
            // ringtones should be available by the time this code executes. We can safely
            // request the custom ringtone from the call and expect it to be current.
            mRingtonePlayer.play(mRingtoneFactory, foregroundCall, startVolume, rampUpTime);
            effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
        } else {
            Log.i(this, "startRinging: skipping because ringer would not be audible. " +
                    "isVolumeOverZero=%s, shouldRingForContact=%s, isRingtonePresent=%s",
                    isVolumeOverZero, shouldRingForContact, isRingtonePresent);
            effect = mDefaultVibrationEffect;
        }

        boolean dndMode = !isRingerAudible;
        torchMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                 Settings.System.FLASHLIGHT_ON_CALL, 0, UserHandle.USER_CURRENT);

        boolean shouldFlash = (torchMode == 1 && !dndMode) ||
                              (torchMode == 2 && dndMode)  ||
                               torchMode == 3;
        if (shouldFlash) {
            blinkFlashlight();
        }

        if (shouldVibrate(mContext, foregroundCall) && !mIsVibrating && shouldRingForContact) {
            mVibrator.vibrate(effect, VIBRATION_ATTRIBUTES);
            mIsVibrating = true;
        } else if (mIsVibrating) {
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION, "already vibrating");
        }

        return shouldAcquireAudioFocus;
    }

    private void blinkFlashlight() {
        torchToggler = new TorchToggler(mContext);
        torchToggler.execute();
    }

    private VibrationEffect getVibrationEffectForCall(RingtoneFactory factory, Call call) {
        VibrationEffect effect = null;
        Ringtone ringtone = factory.getRingtone(call);
        Uri ringtoneUri = ringtone != null ? ringtone.getUri() : null;
        if (ringtoneUri != null) {
            effect = VibrationEffect.get(ringtoneUri, mContext);
        }

        if (effect == null) {
            effect = mDefaultVibrationEffect;
        }
        return effect;
    }

    public void startCallWaiting(Call call) {
        if (mSystemSettingsUtil.isTheaterModeOn(mContext)) {
            return;
        }

        if (mInCallController.doesConnectedDialerSupportRinging()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING);
            return;
        }

        if (call.isSelfManaged()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Self-managed");
            return;
        }

        Log.v(this, "Playing call-waiting tone.");

        stopRinging();

       if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.VIBRATE_ON_CALLWAITING, 0, UserHandle.USER_CURRENT) == 1) {
            if (mVibrator.hasVibrator()) {
                mVibrator.vibrate(CALL_WAITING_VIBRATION_PATTERN, -1);
            }
        }

        if (mCallWaitingPlayer == null) {
            Log.addEvent(call, LogUtils.Events.START_CALL_WAITING_TONE);
            mCallWaitingCall = call;
            mCallWaitingPlayer =
                    mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
            mCallWaitingPlayer.startTone();
        }
    }

    public void stopRinging() {
        if (mRingingCall != null) {
            Log.addEvent(mRingingCall, LogUtils.Events.STOP_RINGER);
            mRingingCall = null;
        }

        mRingtonePlayer.stop();
        torchToggler.stop();

        if (mIsVibrating) {
            Log.addEvent(mVibratingCall, LogUtils.Events.STOP_VIBRATOR);
            mVibrator.cancel();
            mIsVibrating = false;
            mVibratingCall = null;
        }
    }

    public void stopCallWaiting() {
        Log.v(this, "stop call waiting.");
        if (mCallWaitingPlayer != null) {
            if (mCallWaitingCall != null) {
                Log.addEvent(mCallWaitingCall, LogUtils.Events.STOP_CALL_WAITING_TONE);
                mCallWaitingCall = null;
            }

            mCallWaitingPlayer.stopTone();
            mCallWaitingPlayer = null;
        }
    }

    private boolean shouldRingForContact(Uri contactUri) {
        final NotificationManager manager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        final Bundle peopleExtras = new Bundle();
        if (contactUri != null) {
            ArrayList<Person> personList = new ArrayList<>();
            personList.add(new Person.Builder().setUri(contactUri.toString()).build());
            peopleExtras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, personList);
        }
        return manager.matchesCallFilter(peopleExtras);
    }

    private boolean hasExternalRinger(Call foregroundCall) {
        Bundle intentExtras = foregroundCall.getIntentExtras();
        if (intentExtras != null) {
            return intentExtras.getBoolean(TelecomManager.EXTRA_CALL_EXTERNAL_RINGER, false);
        } else {
            return false;
        }
    }

    private boolean shouldVibrate(Context context, Call call) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerModeInternal();
        boolean shouldVibrate;
        if (getVibrateWhenRinging(context)) {
            shouldVibrate = ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            shouldVibrate = ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }

        // Technically this should be in the calling method, but it seemed a little odd to pass
        // around a whole bunch of state just for logging purposes.
        if (shouldVibrate) {
            Log.addEvent(call, LogUtils.Events.START_VIBRATOR,
                    "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                    mVibrator.hasVibrator(), mSystemSettingsUtil.canVibrateWhenRinging(context),
                    ringerMode, mIsVibrating);
        } else {
            Log.addEvent(call, LogUtils.Events.SKIP_VIBRATION,
                    "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                    mVibrator.hasVibrator(), mSystemSettingsUtil.canVibrateWhenRinging(context),
                    ringerMode, mIsVibrating);
        }

        return shouldVibrate;
    }

    private boolean getVibrateWhenRinging(Context context) {
        if (!mVibrator.hasVibrator()) {
            return false;
        }
        return mSystemSettingsUtil.canVibrateWhenRinging(context);
    }

    private class TorchToggler extends AsyncTask {

        private boolean shouldStop = false;
        private CameraManager cameraManager;
        private int duration = 500;
        private boolean hasFlash = true;
        private Context context;

        public TorchToggler(Context ctx) {
            this.context = ctx;
            init();
        }

        private void init() {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            hasFlash = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        }

        void stop() {
            shouldStop = true;
        }

        @Override
        protected Object doInBackground(Object[] objects) {
            if (hasFlash) {
                try {
                    String cameraId = cameraManager.getCameraIdList()[0];
                    while (!shouldStop) {
                        cameraManager.setTorchMode(cameraId, true);
                        Thread.sleep(duration);

                        cameraManager.setTorchMode(cameraId, false);
                        Thread.sleep(duration);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return null;
        }
    }
}
