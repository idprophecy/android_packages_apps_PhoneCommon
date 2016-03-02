/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.android.phone.common.incall;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import com.android.contacts.common.model.AccountTypeManager;
import com.android.contacts.common.model.account.AccountType;
import com.android.contacts.common.model.account.AccountWithDataSet;
import com.android.phone.common.R;
import com.android.phone.common.ambient.AmbientConnection;
import com.android.phone.common.util.StartInCallCallReceiver;
import com.cyanogen.ambient.analytics.Event;
import com.cyanogen.ambient.common.api.AmbientApiClient;
import com.cyanogen.ambient.common.api.Result;
import com.cyanogen.ambient.common.api.ResultCallback;
import com.cyanogen.ambient.discovery.NudgeServices;
import com.cyanogen.ambient.discovery.results.BundleResult;
import com.cyanogen.ambient.discovery.util.NudgeKey;
import com.cyanogen.ambient.incall.InCallApi;
import com.cyanogen.ambient.incall.InCallServices;
import com.cyanogen.ambient.incall.extension.CreditBalance;
import com.cyanogen.ambient.incall.extension.CreditInfo;
import com.cyanogen.ambient.incall.extension.GetCreditInfoResult;
import com.cyanogen.ambient.incall.extension.IAuthenticationListener;
import com.cyanogen.ambient.incall.extension.ICallCreditListener;
import com.cyanogen.ambient.incall.extension.InCallContactInfo;
import com.cyanogen.ambient.incall.extension.StatusCodes;
import com.cyanogen.ambient.incall.results.AuthenticationStateResult;
import com.cyanogen.ambient.incall.results.GetCreditInfoResultResult;
import com.cyanogen.ambient.incall.results.InCallProviderInfoResult;
import com.cyanogen.ambient.incall.results.InstalledPluginsResult;
import com.cyanogen.ambient.incall.results.MimeTypeResult;
import com.cyanogen.ambient.incall.results.PendingIntentResult;
import com.cyanogen.ambient.incall.results.PluginStatusResult;
import com.cyanogen.ambient.incall.util.InCallProviderInfo;
import com.cyanogen.ambient.plugin.PluginStatus;
import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cyanogen.ambient.incall.util.InCallHelper.NO_COLOR;

/**
 *  Call Method Helper - In charge of keeping a running and updated hashmap of all InCallProviders
 *  currently installed.
 *
 *  Fragments and Activities can subscribe to changes with subscribe.
 *
 */
public class CallMethodHelper {

    protected static CallMethodHelper sInstance;

    protected AmbientApiClient mClient;
    protected Context mContext;
    protected InCallApi mInCallApi;
    protected Handler mMainHandler;
    protected static List<ComponentName> mInstalledPlugins;
    protected static HashMap<ComponentName, CallMethodInfo> mCallMethodInfos = new HashMap<>();
    protected static HashMap<ComponentName, ICallCreditListener> mCallCreditListeners = new
            HashMap<>();
    protected static HashMap<ComponentName, IAuthenticationListener> mAuthenticationListeners = new
            HashMap<>();
    protected static HashMap<String, CallMethodReceiver> mRegisteredClients = new HashMap<>();
    protected static boolean dataHasBeenBroadcastPreviously = false;
    // To prevent multiple broadcasts and force us to wait for all items to be complete
    // this is the count of callbacks we should get for each item. Increase this if we add more.
    private static int EXPECTED_RESULT_CALLBACKS = 10;
    protected static int expectedCallbacks = EXPECTED_RESULT_CALLBACKS;

    // Keeps track of the number of callbacks we have from AmbientCore. Reset this to 0
    // immediately after all callbacks are accounted for.
    private static int callbackCount = 0;
    // determine which info types to load
    protected static final String TAG = CallMethodHelper.class.getSimpleName();
    protected static final boolean DEBUG = false;

    public interface CallMethodReceiver {
        void onChanged(HashMap<ComponentName, CallMethodInfo> callMethodInfos);
    }

    /**
     * Broadcasts mCallMethodInfos to all registered clients on the Main thread.
     */
    protected static void broadcast(final boolean asap) {
        getInstance().mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (DEBUG) Log.d(TAG, "broadcast");
                for (CallMethodReceiver client : mRegisteredClients.values()) {
                    client.onChanged(mCallMethodInfos);
                }
                enableListeners();
                if (DEBUG) {
                    for (CallMethodInfo cmi : mCallMethodInfos.values()) {
                        Log.v(TAG, "Broadcast: " + cmi.mName);
                    }
                }
                if (!asap) {
                    dataHasBeenBroadcastPreviously = true;
                    callbackCount = 0;
                }
            }
        });
    }

    private static void enableListeners() {
        if (DEBUG) Log.d(TAG, "Enabling Listeners");
        for (ComponentName callProviders : mCallMethodInfos.keySet()) {
            if (!mCallCreditListeners.containsKey(callProviders)) {
                CallCreditListenerImpl listener =
                        CallCreditListenerImpl.getInstance(callProviders);
                getInstance().mInCallApi.addCreditListener(getInstance().mClient, callProviders,
                        listener);
                mCallCreditListeners.put(callProviders, listener);
            }
            if (!mAuthenticationListeners.containsKey(callProviders)) {
                AuthenticationListenerImpl listener =
                        AuthenticationListenerImpl.getInstance(callProviders);
                getInstance().mInCallApi.addAuthenticationListener(getInstance().mClient,
                        callProviders, listener);
                mAuthenticationListeners.put(callProviders, listener);
            }
        }
    }

    private static void disableListeners() {
        if (DEBUG) Log.d(TAG, "Disabling Listeners");
        for(ComponentName callCreditProvider : mCallCreditListeners.keySet()) {
            if (mCallCreditListeners.get(callCreditProvider) != null) {
                getInstance().mInCallApi.removeCreditListener(getInstance().mClient,
                        callCreditProvider, mCallCreditListeners.get(callCreditProvider));
            }
        }
        for (ComponentName plugin : mAuthenticationListeners.keySet()) {
            if (mAuthenticationListeners.get(plugin) != null) {
                getInstance().mInCallApi.removeAuthenticationListener(getInstance().mClient,
                        plugin, mAuthenticationListeners.get(plugin));
            }
        }
        mCallCreditListeners.clear();
        mAuthenticationListeners.clear();
    }

    /**
     * Helper method for subscribed clients to remove any item that is not enabled from the hashmap
     * @param input HashMap returned from a broadcast
     * @param output HashMap with only enabled items
     */
    public static void removeDisabled(HashMap<ComponentName, CallMethodInfo> input,
                                      HashMap<ComponentName, CallMethodInfo> output) {
        for (Map.Entry<ComponentName, CallMethodInfo> entry : input.entrySet()) {
            ComponentName key = entry.getKey();
            CallMethodInfo value = entry.getValue();

            if (value.mStatus == PluginStatus.ENABLED) {
                output.put(key, value);
            }
        }
    }

    public static HashMap<ComponentName, CallMethodInfo> getAllEnabledCallMethods() {
        HashMap<ComponentName, CallMethodInfo> cmi = new HashMap<ComponentName, CallMethodInfo>();
        for (Map.Entry<ComponentName, CallMethodInfo> entry : mCallMethodInfos.entrySet()) {
            ComponentName key = entry.getKey();
            CallMethodInfo value = entry.getValue();

            if (value.mStatus == PluginStatus.ENABLED) {
                cmi.put(key, value);
            }
        }
        return cmi;
    }

    public static HashMap<ComponentName, CallMethodInfo> getAllEnabledAndHiddenCallMethods() {
        HashMap<ComponentName, CallMethodInfo> cmi = new HashMap<ComponentName, CallMethodInfo>();
        synchronized (mCallMethodInfos) {
            for (Map.Entry<ComponentName, CallMethodInfo> entry : mCallMethodInfos.entrySet()) {
                ComponentName key = entry.getKey();
                CallMethodInfo value = entry.getValue();

                if (value.mStatus == PluginStatus.ENABLED || value.mStatus == PluginStatus.HIDDEN) {
                    cmi.put(key, value);
                }
            }
        }
        return cmi;
    }

    public static CallMethodInfo getMethodForMimeType(String mimeType, boolean enableOnly) {
        CallMethodInfo targetEntry = null;
        synchronized (mCallMethodInfos) {
            for (CallMethodInfo entry : mCallMethodInfos.values()) {
                // TODO: find out why mimetype may be null
                if (!TextUtils.isEmpty(entry.mMimeType)) {
                    if (enableOnly && entry.mStatus != PluginStatus.ENABLED) {
                        continue;
                    }
                    if (entry.mMimeType.equals(mimeType)) {
                        targetEntry = entry;
                        break;
                    }
                }
            }
        }
        return targetEntry;
    }

    /***
     * Registers the client, on register returns boolean if
     * callMethodInfo data is already collected and the initial broadcast has been sent.
     * @param id unique string for the client
     * @param cmr client receiver
     * @return boolean isempty
     */
    public static synchronized boolean subscribe(String id, CallMethodReceiver cmr) {
        mRegisteredClients.put(id, cmr);

        return dataHasBeenBroadcastPreviously;
    }

    /**
     * Unsubscribes the client. All clients should unsubscribe when they are removed.
     * @param id of the client to remove
     */
    public static synchronized void unsubscribe(String id) {
        mRegisteredClients.remove(id);
        disableListeners();
    }

    /**
     * Get a single instance of our call method helper. There should only be ever one instance.
     * @return
     */
    protected static synchronized CallMethodHelper getInstance() {
        if (sInstance == null) {
            sInstance = new CallMethodHelper();
        }
        return sInstance;
    }

    /**
     * Generic CallResultReceiver with basic error handling
     * @param cmi
     * @return
     */
    public static StartInCallCallReceiver getVoIPResultReceiver(final CallMethodInfo cmi,
                                                                    final String originCode) {
        StartInCallCallReceiver svcrr =
                new StartInCallCallReceiver(new Handler(Looper.getMainLooper()));

        svcrr.setReceiver(new StartInCallCallReceiver.Receiver() {

            @Override
            public void onReceiveResult(int resultCode, Bundle resultData) {
                if (DEBUG) Log.i(TAG, "Got Start VoIP Call result callback code = " + resultCode);

                switch (resultCode) {
                    case StatusCodes.StartCall.CALL_FAILURE_INSUFFICIENT_CREDITS:
                    case StatusCodes.StartCall.CALL_FAILURE_INVALID_NUMBER:
                    case StatusCodes.StartCall.CALL_FAILURE_TIMEOUT:
                    case StatusCodes.StartCall.CALL_FAILURE_UNAUTHENTICATED:
                    case StatusCodes.StartCall.CALL_FAILURE:

                        String text = getInstance().mContext.getResources()
                                .getString(R.string.invalid_number_text);
                        text = String.format(text, cmi.mName);
                        Toast.makeText(getInstance().mContext, text, Toast.LENGTH_LONG).show();
                        break;
                    case StatusCodes.StartCall.CALL_CONNECTED:
                        break;
                    case StatusCodes.StartCall.HANDOVER_CONNECTED:
                        break;
                    default:
                        Log.i(TAG, "Nothing to do for this Start VoIP Call resultcode = "
                                + resultCode);
                        break;
                }

            }

        });

        return svcrr;
    }

    /**
     * Start our Helper and kick off our first ModCore queries.
     * @param context
     */
    public static void init(Context context) {
        CallMethodHelper helper = getInstance();
        helper.mContext = context;
        helper.mClient = AmbientConnection.CLIENT.get(context);
        helper.mInCallApi = InCallServices.getInstance();
        helper.mMainHandler = new Handler(context.getMainLooper());
        refresh();
    }

    /**
     * *sip* ahhhh so refreshing
     */
    public static void refresh() {
        updateCallPlugins();
    }

    /**
     * This is helpful for items that don't want to subscribe to updates or for things that
     * need a quick CMI and have a component name.
     * @param cn Component name wanted.
     * @return specific call method when given a component name.
     */
    public static CallMethodInfo getCallMethod(ComponentName cn) {
        CallMethodInfo cmi = null;
        synchronized (mCallMethodInfos) {
            if (mCallMethodInfos.containsKey(cn)) {
                cmi = mCallMethodInfos.get(cn);
            }
        }
        return cmi;
    }

    /**
     * This is useful for items that subscribe after the initial broadcast has been sent out and
     * need to go get some data right away
     * @return the current HashMap of CMIs.
     */
    public static HashMap<ComponentName, CallMethodInfo> getAllCallMethods() {
        // after the initial broadcast on resume we need to go and get some new data
        // this data will broadcast as soon as it becomes available
        return mCallMethodInfos;
    }

    public static void refreshDynamicItems() {
        enableListeners();
        for (ComponentName cn : mCallMethodInfos.keySet()) {
            getCallMethodAuthenticated(cn, true);
            getCreditInfo(cn, true);
        }
    }

    /**
     * A few items need a list of mime types in a comma delimited list. Since we are already
     * querying all the plugins. We can easily build this list ahead of time.
     *
     * Items that require this should subscribe and grab this updated list when needed.
     * @return string of all (not limited to enabled) mime types
     */
    public static String getAllMimeTypes() {
        String mimeTypes = "";

        List<String> mimeTypesList = new ArrayList<>();
        synchronized (mCallMethodInfos) {
            for (CallMethodInfo cmi : mCallMethodInfos.values()) {
                mimeTypesList.add(cmi.mMimeType);
            }
        }

        if (!mimeTypesList.isEmpty()) {
            mimeTypes = Joiner.on(",").skipNulls().join(mimeTypesList);
        }
        return mimeTypes;
    }

    /**
     * A few items need a list of mime types in a comma delimited list. Since we are already
     * querying all the plugins. We can easily build this list ahead of time.
     *
     * Items that require this should subscribe and grab this updated list when needed.
     * @return string of enabled mime types
     */
    public static String getAllEnabledMimeTypes() {
        String mimeTypes = "";

        List<String> enabledMimeTypes = new ArrayList<>();
        synchronized (mCallMethodInfos) {
            for (CallMethodInfo cmi : mCallMethodInfos.values()) {
                if (cmi.mStatus == PluginStatus.ENABLED) {
                    enabledMimeTypes.add(cmi.mMimeType);
                }
            }
        }

        if (!enabledMimeTypes.isEmpty()) {
            mimeTypes = Joiner.on(",").skipNulls().join(enabledMimeTypes);
        }
        return mimeTypes;
    }

    /**
     * A few items need a list of video callable mime types in a comma delimited list.
     * Since we are already querying all the plugins. We can easily build this list ahead of time.
     *
     * Items that require this should subscribe and grab this updated list when needed.
     * @return string of enabled video callable mime types
     */
    public static String getAllEnabledVideoCallableMimeTypes() {
        String mimeTypes = "";

        List<String> enabledMimeTypes = new ArrayList<>();
        synchronized (mCallMethodInfos) {
            for (CallMethodInfo cmi : mCallMethodInfos.values()) {
                if (cmi.mStatus == PluginStatus.ENABLED) {
                    enabledMimeTypes.add(cmi.mVideoCallableMimeType);
                }
            }
        }

        if (!enabledMimeTypes.isEmpty()) {
            mimeTypes = Joiner.on(",").skipNulls().join(enabledMimeTypes);
        }
        return mimeTypes;
    }

    public static String getAllEnabledImMimeTypes() {
        String mimeTypes = "";

        List<String> enabledMimeTypes = new ArrayList<>();
        synchronized (mCallMethodInfos) {
            for (CallMethodInfo cmi : mCallMethodInfos.values()) {
                if (cmi.mStatus == PluginStatus.ENABLED) {
                    enabledMimeTypes.add(cmi.mImMimeType);
                }
            }
        }
        if (!enabledMimeTypes.isEmpty()) {
            mimeTypes = Joiner.on(",").skipNulls().join(enabledMimeTypes);
        }
        return mimeTypes;
    }

    public static void updateCreditInfo(ComponentName name, GetCreditInfoResult gcir) {
        CallMethodInfo cmi = getCallMethodIfExists(name);
        if (cmi != null) {
            if (gcir == null || gcir.creditInfo == null) {
                // Build zero credit dummy if no result found.
                cmi.mProviderCreditInfo =
                        new CreditInfo(new CreditBalance(0, null), null);
            } else {
                cmi.mProviderCreditInfo = gcir.creditInfo;
            }

            // Since a CallMethodInfo object was updated here, we should let the subscribers know
            broadcast(true);
        }
    }

    public static void updateAuthenticationState(ComponentName name, int state) {
        CallMethodInfo cmi = getCallMethodIfExists(name);
        if (cmi != null) {
            cmi.mIsAuthenticated = state == StatusCodes.AuthenticationState
                    .LOGGED_IN;
            mCallMethodInfos.put(name, cmi);

            // Since a CallMethodInfo object was updated here, we should let the subscribers know
            broadcast(true);
        }
    }

    /**
     * Broadcast to subscribers once we know we've gathered all our data. Do not do this until we
     * have everything we need for sure.
     *
     * This method is called after every callback from AmbientCore. We will keep track of all of
     * the callbacks, once we have accounted for all callbacks from all plugins, we can go ahead
     * and update subscribers.
     */
    protected static void maybeBroadcastToSubscribers() {
        ++callbackCount;

        if (DEBUG) Log.d(TAG, "maybeBroadcastToSubscribers: mInstalledPugins:" + mInstalledPlugins
                .size());
        if (callbackCount == (expectedCallbacks  * mInstalledPlugins.size()))  {
            // we are on the last item. broadcast updated hashmap
            broadcast(false);
        }
    }

    /**
     * In order to speed up the process we make calls for providers that may be invalid
     * To prevent this, make sure every resultcallback uses this before filling in the hashmap.
     * @param cn componentname
     * @return callmethodinfo if valid, otherwise null
     */
    public static CallMethodInfo getCallMethodIfExists(ComponentName cn) {
        if (mCallMethodInfos.containsKey(cn)) {
            return mCallMethodInfos.get(cn);
        } else {
            return null;
        }
    }

    /**
     * Prepare to query and fire off ModCore calls in all directions
     */
    protected static void updateCallPlugins() {
        getInstance().mInCallApi.getInstalledPlugins(getInstance().mClient)
                .setResultCallback(new ResultCallback<InstalledPluginsResult>() {
            @Override
            public void onResult(InstalledPluginsResult installedPluginsResult) {
                if (DEBUG) Log.d(TAG, "+++updateCallPlugins");
                // got installed components
                mInstalledPlugins = installedPluginsResult.components;

                mCallMethodInfos.clear();

                if (mInstalledPlugins.size() == 0) {
                    broadcast(false);
                }

                for (ComponentName cn : mInstalledPlugins) {
                    mCallMethodInfos.put(cn, new CallMethodInfo());
                    getCallMethodInfo(cn);
                    getCallMethodStatus(cn);
                    getCallMethodMimeType(cn);
                    getCallMethodVideoCallableMimeType(cn);
                    getCallMethodAuthenticated(cn, false);
                    getLoginIntent(cn);
                    getSettingsIntent(cn);
                    getCreditInfo(cn, false);
                    getManageCreditsIntent(cn);
                    checkLowCreditConfig(cn);
                    // If you add any more callbacks, be sure to update EXPECTED_RESULT_CALLBACKS
                    // and EXPECTED_DYNAMIC_RESULT_CALLBACKS if the callback is dynamic
                    // with the proper count.
                }
            }
        });
    }

    protected static void getCallMethodInfo(final ComponentName cn) {
        getInstance().mInCallApi.getProviderInfo(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<InCallProviderInfoResult>() {
                    @Override
                    public void onResult(InCallProviderInfoResult inCallProviderInfoResult) {
                        InCallProviderInfo icpi = inCallProviderInfoResult.inCallProviderInfo;
                        if (icpi == null) {
                            mCallMethodInfos.remove(cn);
                            return;
                        }

                        PackageManager packageManager = getInstance().mContext.getPackageManager();
                        Resources pluginResources = null;
                        try {
                            pluginResources = packageManager.getResourcesForApplication(
                                    cn.getPackageName());
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(TAG, "Plugin isn't installed: " + cn);
                            mCallMethodInfos.remove(cn);
                            return;
                        }

                        // Gather account handles logged into the device as a backup, in case
                        // plugins fail to return the account handle even when it reports its
                        // state as authenticated
                        AccountTypeManager accountTypes = AccountTypeManager.getInstance
                                (getInstance().mContext);
                        List<AccountWithDataSet> accounts = accountTypes.getAccounts(false);
                        ArrayMap<String, String> accountMap = new ArrayMap<String, String>();

                        for (AccountWithDataSet account : accounts) {
                            AccountType accountType =
                                    accountTypes.getAccountType(account.type, account.dataSet);
                            if (accountType.isExtension() &&
                                    !account.hasData(getInstance().mContext)) {
                                // Hide extensions with no raw_contacts.
                                continue;
                            }
                            if (DEBUG)
                                Log.d(TAG, "account.type: " + account.type + "account.name: " +
                                        account.name);
                            // currently only handle one account per account type use case
                            accountMap.put(account.type, account.name);
                        }

                        synchronized (mCallMethodInfos) {
                            CallMethodInfo cmi = getCallMethodIfExists(cn);

                            if (cmi == null) {
                                return;
                            }

                            try {
                                cmi.mSingleColorBrandIcon =
                                        pluginResources.getDrawable(icpi.getSingleColorBrandIcon(),
                                                null);
                                cmi.mActionOneIcon =
                                        pluginResources.getDrawable(icpi.getActionOneIcon(), null);
                                cmi.mActionTwoIcon =
                                        pluginResources.getDrawable(icpi.getActionTwoIcon(), null);
                                cmi.mBrandIcon =
                                        pluginResources.getDrawable(icpi.getBrandIcon(), null);
                                cmi.mLoginIcon =
                                        pluginResources.getDrawable(icpi.getLoginIcon(), null);
                                cmi.mVoiceIcon = pluginResources.getDrawable(icpi
                                        .getVoiceMimeIcon(), null);
                                cmi.mVideoIcon = pluginResources.getDrawable(icpi
                                        .getVideoMimeIcon(), null);
                                cmi.mImIcon = pluginResources.getDrawable(icpi.getImMimeIcon(),
                                        null);
                                cmi.mBadgeIcon = pluginResources.getDrawable(icpi.getBadgeIcon(),
                                        null);

                            } catch (Resources.NotFoundException e) {
                                Log.e(TAG, "Resource Not found: " + cn);
                                mCallMethodInfos.remove(cn);
                                return;
                            }

                            cmi.mSlotId = -1;
                            cmi.mSubId = -1;
                            cmi.mColor = NO_COLOR;
                            cmi.mSubscriptionButtonText = icpi.getSubscriptionButtonText();
                            cmi.mCreditButtonText = icpi.getCreditsButtonText();
                            cmi.mT9HintDescription = icpi.getT9HintDescription();
                            cmi.pluginResources = pluginResources;
                            cmi.mActionOneText = icpi.getActionOneTitle();
                            cmi.mActionTwoText = icpi.getActionTwoTitle();
                            cmi.mIsInCallProvider = true;

                            cmi.mComponent = cn;
                            cmi.mNudgeComponent = icpi.getNudgeComponent() == null ? null :
                                    ComponentName.unflattenFromString(icpi.getNudgeComponent());
                            cmi.mName = icpi.getTitle();
                            cmi.mSummary = icpi.getSummary();
                            cmi.mAccountType = icpi.getAccountType();
                            cmi.mAccountHandle = icpi.getAccountHandle();
                            if (TextUtils.isEmpty(cmi.mAccountHandle)) {
                                if (accountMap.containsKey(cmi.mAccountType)) {
                                    cmi.mAccountHandle = accountMap.get(cmi.mAccountHandle);
                                }
                            }
                            cmi.mBrandIconId = icpi.getBrandIcon();
                            cmi.mLoginIconId = icpi.getLoginIcon();

                            cmi.mAccountType = icpi.getAccountType();
                            mCallMethodInfos.put(cn, cmi);
                            maybeBroadcastToSubscribers();
                        }
                    }
                });
    }

    /**
     * Get our plugin enabled status
     * @param cn
     */
    protected static void getCallMethodStatus(final ComponentName cn) {
        getInstance().mInCallApi.getPluginStatus(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<PluginStatusResult>() {
            @Override
            public void onResult(PluginStatusResult pluginStatusResult) {
                synchronized (mCallMethodInfos) {
                    CallMethodInfo cmi = getCallMethodIfExists(cn);
                    if (cmi != null) {
                        cmi.mStatus = pluginStatusResult.status;
                        mCallMethodInfos.put(cn, cmi);
                        maybeBroadcastToSubscribers();
                    }
                }
            }
        });
    }

    /**
     * Send an event to the component
     * @param cn componentName to send the data to.
     */
    public static void shipAnalyticsToPlugin(final ComponentName cn, Event e) {
        if (cn == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "componentName: " + cn.toShortString());
            Log.d(TAG, "Event: " + e.toString());
        }
        getInstance().mInCallApi.sendAnalyticsEventToPlugin(getInstance().mClient, cn, e)
                .setResultCallback(new ResultCallback<Result>() {
            @Override
            public void onResult(Result result) {
                if (DEBUG) {
                    Log.v(TAG, "Event sent with result: " + result.getStatus().getStatusMessage());
                }
            }
        });
    }

    /**
     * Get the call method mime type
     * @param cn
     */
    protected static void getCallMethodMimeType(final ComponentName cn) {
        getInstance().mInCallApi.getCallableMimeType(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<MimeTypeResult>() {
            @Override
            public void onResult(MimeTypeResult mimeTypeResult) {
                synchronized (mCallMethodInfos) {
                    CallMethodInfo cmi = getCallMethodIfExists(cn);
                    if (cmi != null) {
                        cmi.mMimeType = mimeTypeResult.mimeType;
                        mCallMethodInfos.put(cn, cmi);
                        maybeBroadcastToSubscribers();
                    }
                }
            }
        });
    }

    /**
     * Get the call method mime type
     * @param cn
     */
    protected static void getCallMethodVideoCallableMimeType(final ComponentName cn) {
        getInstance().mInCallApi.getVideoCallableMimeType(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<MimeTypeResult>() {
                    @Override
                    public void onResult(MimeTypeResult mimeTypeResult) {
                        synchronized (mCallMethodInfos) {
                            CallMethodInfo cmi = getCallMethodIfExists(cn);
                            if (cmi != null) {
                                cmi.mVideoCallableMimeType = mimeTypeResult.mimeType;
                                mCallMethodInfos.put(cn, cmi);
                                maybeBroadcastToSubscribers();
                            }
                        }
                    }
                });
    }

    /**
     * Get the IM mime type
     * @param cn
     */
    protected static void getCallMethodImMimeType(final ComponentName cn) {
        getInstance().mInCallApi.getImMimeType(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<MimeTypeResult>() {
                    @Override
                    public void onResult(MimeTypeResult mimeTypeResult) {
                        synchronized (mCallMethodInfos) {
                            CallMethodInfo cmi = getCallMethodIfExists(cn);
                            if (cmi != null) {
                                cmi.mImMimeType = mimeTypeResult.mimeType;
                                mCallMethodInfos.put(cn, cmi);
                                maybeBroadcastToSubscribers();
                            }
                        }
                    }
                });
    }

    /**
     * Get the Authentication state of the callmethod
     * @param cn
     */
    protected static void getCallMethodAuthenticated(final ComponentName cn,
            final boolean broadcastASAP) {
        getInstance().mInCallApi.getAuthenticationState(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<AuthenticationStateResult>() {
            @Override
            public void onResult(AuthenticationStateResult result) {
                synchronized (mCallMethodInfos) {
                    CallMethodInfo cmi = getCallMethodIfExists(cn);
                    if (cmi != null) {
                        cmi.mIsAuthenticated = result.result == StatusCodes.AuthenticationState
                                .LOGGED_IN;
                        mCallMethodInfos.put(cn, cmi);
                        if (broadcastASAP) {
                            broadcast(true);
                        } else {
                            maybeBroadcastToSubscribers();
                        }
                    }
                }
            }
        });
    }

    /**
     * Get the settings intent for the callmethod
     * @param cn
     */
    private static void getSettingsIntent(final ComponentName cn) {
        getInstance().mInCallApi.getSettingsIntent(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<PendingIntentResult>() {
                    @Override
                    public void onResult(PendingIntentResult pendingIntentResult) {
                        synchronized (mCallMethodInfos) {
                            CallMethodInfo cmi = getCallMethodIfExists(cn);
                            if (cmi != null) {
                                cmi.mSettingsIntent = pendingIntentResult.intent;
                                mCallMethodInfos.put(cn, cmi);
                                maybeBroadcastToSubscribers();
                            }
                        }
                    }
                });
    }

    private static void getCreditInfo(final ComponentName cn, final boolean broadcastASAP) {
        getInstance().mInCallApi.getCreditInfo(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<GetCreditInfoResultResult>() {
                    @Override
                    public void onResult(GetCreditInfoResultResult getCreditInfoResultResult) {
                        CallMethodInfo cmi = getCallMethodIfExists(cn);
                        if (cmi != null) {
                            GetCreditInfoResult gcir = getCreditInfoResultResult.result;
                            if (gcir == null || gcir.creditInfo == null) {
                                // Build zero credit dummy if no result found.
                                cmi.mProviderCreditInfo =
                                        new CreditInfo(new CreditBalance(0, null), null);
                            } else {
                                cmi.mProviderCreditInfo = gcir.creditInfo;
                            }
                            mCallMethodInfos.put(cn, cmi);
                            if (broadcastASAP) {
                                broadcast(true);
                            } else {
                                maybeBroadcastToSubscribers();
                            }
                        }
                    }
                });
    }

    private static void getManageCreditsIntent(final ComponentName cn) {
        getInstance().mInCallApi.getManageCreditsIntent(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<PendingIntentResult>() {
                    @Override
                    public void onResult(PendingIntentResult pendingIntentResult) {
                        CallMethodInfo cmi = getCallMethodIfExists(cn);
                        if (cmi != null) {
                            cmi.mManageCreditIntent = pendingIntentResult.intent;
                            mCallMethodInfos.put(cn, cmi);
                            maybeBroadcastToSubscribers();
                        }
                    }
                });
    }

    private static void checkLowCreditConfig(final ComponentName cn) {
        // find a nudge component if it exists for this package
        Intent nudgeIntent = new Intent("cyanogen.service.NUDGE_PROVIDER");
        nudgeIntent.setPackage(cn.getPackageName());
        List<ResolveInfo> resolved = getInstance().mContext.getPackageManager()
                .queryIntentServices(nudgeIntent, 0);
        if (resolved != null && !resolved.isEmpty()) {
            ResolveInfo result = resolved.get(0);
            ComponentName nudgeComponent = new ComponentName(result.serviceInfo.applicationInfo
                    .packageName, result.serviceInfo.name);
            collectLowCreditConfig(cn, nudgeComponent);
            return;
        }

        // if a nudge component doesn't exist, just finish here
        maybeBroadcastToSubscribers();
    }

    private static void collectLowCreditConfig(final ComponentName pluginComponent, final
                                               ComponentName nudgeComponent) {
        NudgeServices.NudgeApi.getConfigurationForKey(getInstance().mClient, nudgeComponent,
                NudgeKey.INCALL_CREDIT_NUDGE).setResultCallback(new ResultCallback<BundleResult>() {
            @Override
            public void onResult(BundleResult bundleResult) {
                CallMethodInfo cmi = getCallMethodIfExists(pluginComponent);
                if (cmi != null) {
                    if (bundleResult != null && bundleResult.bundle != null &&
                            bundleResult.bundle.containsKey(NudgeKey
                                    .INCALL_PARAM_CREDIT_WARN)) {
                        Object creditWarn = bundleResult.bundle.get(NudgeKey
                                .INCALL_PARAM_CREDIT_WARN);
                        if (creditWarn.getClass().equals(Integer.class)) {
                            cmi.mCreditWarn = (Integer) creditWarn;
                        } else if (creditWarn.getClass().equals(Float.class)) {
                            cmi.mCreditWarn = (Float) creditWarn;
                        } else {
                            Log.e(TAG, "Invalid value for Credit Warn limit: " + creditWarn);
                        }
                        mCallMethodInfos.put(pluginComponent, cmi);
                    }
                    maybeBroadcastToSubscribers();
                }
            }
        });
    }

    protected static void getLoginIntent(final ComponentName cn) {
        getInstance().mInCallApi.getLoginIntent(getInstance().mClient, cn)
                .setResultCallback(new ResultCallback<PendingIntentResult>() {
                    @Override
                    public void onResult(PendingIntentResult pendingIntentResult) {
                        synchronized (mCallMethodInfos) {
                            CallMethodInfo cmi = getCallMethodIfExists(cn);
                            if (cmi != null) {
                                cmi.mLoginIntent = pendingIntentResult.intent;
                                mCallMethodInfos.put(cn, cmi);
                                maybeBroadcastToSubscribers();
                            }
                        }
                    }
                });
    }

    /**
     * Get the the contact directory search intent with a callback
     * @param cn
     */
    protected static void getDefaultDirectorySearchIntent(final ComponentName cn) {
        getInstance().mInCallApi.getDirectorySearchIntent(getInstance().mClient, cn, Uri.parse(""))
                .setResultCallback(new ResultCallback<PendingIntentResult>() {
                    @Override
                    public void onResult(PendingIntentResult pendingIntentResult) {
                        synchronized (mCallMethodInfos) {
                            CallMethodInfo cmi = getCallMethodIfExists(cn);
                            if (cmi != null) {
                                cmi.mDefaultDirectorySearchIntent = pendingIntentResult.intent;
                                maybeBroadcastToSubscribers();
                            }
                        }
                    }
                });
    }

    /**
     * Get the the invite intent
     * @param cn
     */
    protected static void getInviteIntent(final ComponentName cn, InCallContactInfo contactInfo) {
        getInstance().mInCallApi.getInviteIntent(getInstance().mClient, cn,
                contactInfo).setResultCallback(new ResultCallback<PendingIntentResult>() {
            @Override
            public void onResult(PendingIntentResult pendingIntentResult) {
                synchronized (mCallMethodInfos) {
                    CallMethodInfo cmi = getCallMethodIfExists(cn);
                    if (cmi != null) {
                        cmi.mInviteIntent = pendingIntentResult.intent;
                    }
                }
            }
        });
    }

    /**
     * Get the the contact directory search intent with a callback
     * @param cn
     * @param contactInfo contact info containing display name, phone number and contact Uri
     */

    public static PendingIntent getInviteIntentSync(final ComponentName cn, InCallContactInfo
            contactInfo) {
        PendingIntentResult pendingIntentResult =
                getInstance().mInCallApi.getInviteIntent(getInstance().mClient, cn, contactInfo)
                        .await();
        CallMethodInfo cmi = getCallMethodIfExists(cn);
        if (cmi != null) {
            cmi.mInviteIntent = pendingIntentResult.intent;
        }
        return cmi.mInviteIntent;
    }

    /**
     * Get the the contact directory search intent with a callback
     * @param cn
     * @param contactUri contact lookup Uri
     */
    protected static void getDirectorySearchIntent(final ComponentName cn, Uri contactUri) {
        //InCallContactInfo contactInfo = new InCallContactInfo(null, null, Uri.parse(""));
        getInstance().mInCallApi.getDirectorySearchIntent(getInstance().mClient, cn, contactUri)
                .setResultCallback(new ResultCallback<PendingIntentResult>() {
                    @Override
                    public void onResult(PendingIntentResult pendingIntentResult) {
                        synchronized (mCallMethodInfos) {
                            CallMethodInfo cmi = getCallMethodIfExists(cn);
                            if (cmi != null) {
                                cmi.mDirectorySearchIntent = pendingIntentResult.intent;
                            }
                        }
                    }
                });
    }

    /**
     * Get the the contact directory search intent with a blocking call
     * @param cn
     * @param contactUri contact lookup Uri
     */

    public static PendingIntent getDirectorySearchIntentSync(final ComponentName cn, Uri
            contactUri) {
        PendingIntentResult pendingIntentResult =
                getInstance().mInCallApi.getDirectorySearchIntent(getInstance().mClient, cn,
                        contactUri).await();
        CallMethodInfo cmi = getCallMethodIfExists(cn);
        if (cmi != null) {
            cmi.mDirectorySearchIntent = pendingIntentResult.intent;
        }
        return cmi.mDirectorySearchIntent;
    }

    protected static ComponentName getNudgeComponent(final ComponentName cn) {
        // find a nudge component if it exists for this package
        Intent nudgeIntent = new Intent("cyanogen.service.NUDGE_PROVIDER");
        nudgeIntent.setPackage(cn.getPackageName());
        List<ResolveInfo> resolved = getInstance().mContext.getPackageManager()
                .queryIntentServices(nudgeIntent, 0);
        if (resolved != null && !resolved.isEmpty()) {
            ResolveInfo result = resolved.get(0);
            return new ComponentName(result.serviceInfo.applicationInfo
                    .packageName, result.serviceInfo.name);
        }
        // if a nudge component doesn't exist, just finish here
        return null;
    }

    protected static void getNudgeConfiguration(final ComponentName cn, final String key) {
        final ComponentName nudgeComponent;

        com.android.phone.common.incall.CallMethodInfo cm = null;
        synchronized (mCallMethodInfos) {
            cm = getCallMethodIfExists(cn);
        }
        if (cm == null) {
            return;
        }

        if (cm.mNudgeComponent == null) {
            nudgeComponent = getNudgeComponent(cn);
            cm.mNudgeComponent = nudgeComponent;
        } else {
            nudgeComponent = cm.mNudgeComponent;
        }
        NudgeServices.NudgeApi.getConfigurationForKey(getInstance().mClient,
                nudgeComponent, key).setResultCallback(new ResultCallback<BundleResult>() {
            @Override
            public void onResult(BundleResult bundleResult) {
                synchronized (mCallMethodInfos) {
                    com.android.phone.common.incall.CallMethodInfo cmi = getCallMethodIfExists(cn);
                    if (bundleResult != null && bundleResult.bundle != null) {
                        Bundle nudgeConfig = bundleResult.bundle;
                        switch (key) {
                            case NudgeKey.INCALL_CONTACT_FRAGMENT_LOGIN:
                                cmi.mLoginSubtitle =
                                        nudgeConfig.getString(NudgeKey.NUDGE_PARAM_SUBTITLE, "");
                                break;
                            case NudgeKey.INCALL_CONTACT_CARD_LOGIN:
                                cmi.mLoginNudgeEnable =
                                        nudgeConfig.getBoolean(NudgeKey.NUDGE_PARAM_ENABLED, true)
                                                &&
                                                PreferenceManager.getDefaultSharedPreferences
                                                        (getInstance().mContext)
                                                        .getBoolean(cn.getClassName() + "." + key,
                                                                true);
                                cmi.mLoginNudgeTitle =
                                        nudgeConfig.getString(NudgeKey.NUDGE_PARAM_TITLE);
                                cmi.mLoginNudgeSubtitle =
                                        nudgeConfig.getString(NudgeKey.NUDGE_PARAM_SUBTITLE);
                                cmi.mLoginNudgeActionText = nudgeConfig.getString(NudgeKey.
                                        NUDGE_PARAM_ACTION_TEXT);
                                break;
                            case NudgeKey.INCALL_CONTACT_CARD_DOWNLOAD:
                                cmi.mInstallNudgeEnable =
                                        nudgeConfig.getBoolean(NudgeKey.NUDGE_PARAM_ENABLED, true)
                                                &&
                                                PreferenceManager.getDefaultSharedPreferences
                                                        (getInstance().mContext)
                                                        .getBoolean(cn.getClassName() + "." + key,
                                                                true);
                                cmi.mInstallNudgeTitle =
                                        nudgeConfig.getString(NudgeKey.NUDGE_PARAM_TITLE);
                                cmi.mInstallNudgeSubtitle =
                                        nudgeConfig.getString(NudgeKey.NUDGE_PARAM_SUBTITLE);
                                cmi.mInstallNudgeActionText = nudgeConfig.getString(NudgeKey.
                                        NUDGE_PARAM_ACTION_TEXT);
                                break;
                            default:
                                break;

                        }
                    }

                    maybeBroadcastToSubscribers();
                }
            }
        });
    }

    public static Set<String> getAllEnabledVoiceMimeSet() {
        String[] mimes = getAllEnabledMimeTypes().split(",");
        HashSet<String> mimeSet = new HashSet<String>();
        if (mimes != null) {
            mimeSet.addAll(Arrays.asList(mimes));
        }
        return mimeSet;
    }

    public static Set<String> getAllEnabledVideoImMimeSet() {
        String[] videoMimes = getAllEnabledVideoCallableMimeTypes().split(",");
        String[] imMimes = getAllEnabledImMimeTypes().split(",");
        HashSet<String> mimeSet = new HashSet<String>();

        if (videoMimes != null) {
            mimeSet.addAll(Arrays.asList(videoMimes));
        }
        if (imMimes != null) {
            mimeSet.addAll(Arrays.asList(imMimes));
        }
        return mimeSet;
    }

    public static boolean infoReady() {
        return dataHasBeenBroadcastPreviously;
    }
}
