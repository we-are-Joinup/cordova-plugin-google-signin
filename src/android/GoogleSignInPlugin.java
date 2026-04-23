package com.devapps;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.Scopes;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class GoogleSignInPlugin extends CordovaPlugin {

    private CredentialManager credentialManager;
    private GoogleIdTokenCredential lastCredential;

    private Context mContext;
    private Activity mCurrentActivity;
    private CallbackContext mCallbackContext;
    private Executor mExecutor;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        mCurrentActivity = this.cordova.getActivity();
        mContext = this.cordova.getActivity().getApplicationContext();
        credentialManager = CredentialManager.create(mContext);
        mExecutor = Executors.newSingleThreadExecutor();
        checkIfOneTapSignInCoolingPeriodShouldBeReset();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {

        if (action.equals(Constants.CORDOVA_ACTION_ONE_TAP_LOGIN)) {
            this.oneTapLogin(callbackContext);
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_IS_SIGNEDIN)) {
            this.isSignedIn(callbackContext);
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_DISCONNECT)) {
            this.disconnect(callbackContext);
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_SIGNIN)) {
            this.signIn(callbackContext);
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_SIGNOUT)) {
            this.signOut(callbackContext);
            return true;
        }
        return false;
    }

    private void oneTapLogin(CallbackContext callbackContext) {
        mCallbackContext = callbackContext;
        processOneTap();
    }

    private void isSignedIn(CallbackContext callbackContext) {
        boolean isSignedIn = (lastCredential != null);
        callbackContext.success(getSuccessMessageInJsonString(String.valueOf(isSignedIn)));
    }

    private void disconnect(CallbackContext callbackContext) {
        callbackContext.error(getErrorMessageInJsonString("Not available on Android."));
    }

    private void signIn(CallbackContext callbackContext) {
        mCallbackContext = callbackContext;
        performSignIn();
    }

    private void signOut(CallbackContext callbackContext) {
        mCallbackContext = callbackContext;
        clearCredentials();
    }

    private void performSignIn() {
        String clientId = mCurrentActivity.getResources().getString(
                getAppResource("default_client_id", "string"));

        GetSignInWithGoogleOption signInOption = new GetSignInWithGoogleOption.Builder(clientId)
                .build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build();

        credentialManager.getCredentialAsync(
                mCurrentActivity,
                request,
                null,
                mExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleSignInCredential(result);
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        mCallbackContext.error(getErrorMessageInJsonString(e.getMessage()));
                    }
                }
        );
    }

    private void handleSignInCredential(GetCredentialResponse response) {
        try {
            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(
                    response.getCredential().getData());
            lastCredential = googleCredential;

            String email = googleCredential.getId();
            String idToken = googleCredential.getIdToken();

            new Thread(() -> {
                try {
                    Account account = new Account(email, "com.google");
                    String accessToken = GoogleAuthUtil.getToken(mContext, account,
                            "oauth2:" + Scopes.EMAIL);

                    JSONObject userInfo = new JSONObject();
                    userInfo.put("id", email);
                    userInfo.put("display_name", googleCredential.getDisplayName());
                    userInfo.put("email", email);
                    userInfo.put("photo_url", googleCredential.getProfilePictureUri());
                    userInfo.put("id_token", idToken);
                    userInfo.put("access_token", accessToken);

                    mCallbackContext.success(getSuccessMessageForOneTapLogin(userInfo));
                } catch (Exception ex) {
                    mCallbackContext.error(getErrorMessageInJsonString(ex.getMessage()));
                }
            }).start();
        } catch (Exception ex) {
            mCallbackContext.error(getErrorMessageInJsonString(ex.getMessage()));
        }
    }

    private void processOneTap() {
        checkIfOneTapSignInCoolingPeriodShouldBeReset();
        SharedPreferences sharedPreferences = getSharedPreferences();
        boolean shouldShowOneTapUI = sharedPreferences.getBoolean(Constants.PREF_SHOW_ONE_TAP_UI, true);

        if (shouldShowOneTapUI) {
            String clientId = mCurrentActivity.getResources().getString(
                    getAppResource("default_client_id", "string"));

            GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(true)
                    .build();

            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build();

            credentialManager.getCredentialAsync(
                    mCurrentActivity,
                    request,
                    null,
                    mExecutor,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleOneTapCredential(result);
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException e) {
                            if (e.getClass().getSimpleName().contains("Cancellation")) {
                                beginOneTapSigninCoolingPeriod();
                            }
                            mCallbackContext.error(getErrorMessageInJsonString(e.getMessage()));
                        }
                    }
            );
        } else {
            mCallbackContext.error(getErrorMessageInJsonString("One Tap Signin was denied by the user."));
        }
    }

    private void handleOneTapCredential(GetCredentialResponse response) {
        try {
            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(
                    response.getCredential().getData());
            lastCredential = googleCredential;

            JSONObject userInfo = new JSONObject();
            userInfo.put("id", googleCredential.getId());
            userInfo.put("display_name", googleCredential.getDisplayName());
            userInfo.put("email", googleCredential.getId());
            userInfo.put("photo_url", googleCredential.getProfilePictureUri());
            userInfo.put("id_token", googleCredential.getIdToken());

            mCallbackContext.success(getSuccessMessageForOneTapLogin(userInfo));
        } catch (Exception ex) {
            mCallbackContext.error(getErrorMessageInJsonString(ex.getMessage()));
        }
    }

    private void clearCredentials() {
        lastCredential = null;

        ClearCredentialStateRequest clearRequest = new ClearCredentialStateRequest();
        credentialManager.clearCredentialStateAsync(
                clearRequest,
                null,
                mExecutor,
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void result) {
                        mCallbackContext.success(getSuccessMessageInJsonString("Logged out"));
                    }

                    @Override
                    public void onError(@NonNull ClearCredentialException e) {
                        mCallbackContext.error(getErrorMessageInJsonString(e.getMessage()));
                    }
                }
        );
    }

    private void beginOneTapSigninCoolingPeriod() {
        SharedPreferences sharedPreferences = getSharedPreferences();
        SharedPreferences.Editor preferences = sharedPreferences.edit();
        preferences.putBoolean(Constants.PREF_SHOW_ONE_TAP_UI, false);
        preferences.putLong(Constants.PREF_COOLING_START_TIME, new Date().getTime());
        preferences.apply();
    }

    private void checkIfOneTapSignInCoolingPeriodShouldBeReset() {
        SharedPreferences sharedPreferences = getSharedPreferences();
        Date now = new Date();
        long coolingStartTime = sharedPreferences.getLong(Constants.PREF_COOLING_START_TIME, now.getTime());

        int daysApart = (int) ((now.getTime() - coolingStartTime) / (1000 * 60 * 60 * 24l));
        if (daysApart >= 1) {
            SharedPreferences.Editor preferences = sharedPreferences.edit();
            preferences.putBoolean(Constants.PREF_SHOW_ONE_TAP_UI, true);
            preferences.putLong(Constants.PREF_COOLING_START_TIME, 0L);
            preferences.apply();
        }
    }

    private String getSuccessMessageForOneTapLogin(JSONObject userInfo) {
        try {
            JSONObject response = new JSONObject();
            response.put(Constants.JSON_STATUS, Constants.JSON_SUCCESS);
            response.put(Constants.JSON_MESSAGE, userInfo);
            return response.toString();
        } catch (JSONException e) {
            return "{\"status\": \"error\", \"message\": \"JSON error while building the response\"}";
        }
    }

    private String getSuccessMessageInJsonString(String message) {
        try {
            JSONObject response = new JSONObject();
            response.put(Constants.JSON_STATUS, Constants.JSON_SUCCESS);
            response.put(Constants.JSON_MESSAGE, message);
            return response.toString();
        } catch (JSONException e) {
            return "{\"status\": \"error\", \"message\": \"JSON error while building the response\"}";
        }
    }

    private String getErrorMessageInJsonString(String errorMessage) {
        try {
            JSONObject response = new JSONObject();
            response.put(Constants.JSON_STATUS, Constants.JSON_ERROR);
            response.put(Constants.JSON_MESSAGE, errorMessage);
            return response.toString();
        } catch (JSONException e) {
            return "{\"status\": \"error\", \"message\": \"JSON error while building the response\"}";
        }
    }

    private int getAppResource(String name, String type) {
        return cordova.getActivity().getResources().getIdentifier(name, type, cordova.getActivity().getPackageName());
    }

    private SharedPreferences getSharedPreferences() {
        return mContext.getSharedPreferences(Constants.PREF_FILENAME, Context.MODE_PRIVATE);
    }
}
